package com.ridebatching.dispatch;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.driver.Driver;
import com.ridebatching.driver.DriverRepository;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.Trip;
import com.ridebatching.trip.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DispatchIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private DriverRepository driverRepository;

    @Autowired
    private DriverOfferRepository offerRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private TripRepository tripRepository;

    private UUID tripId;
    private GeoPoint pickupLocation;
    private Driver nearDriver;
    private Driver farDriver;

    @BeforeEach
    void setup() {
        tripId = UUID.randomUUID();
        Trip trip = new Trip(tripId, java.time.Instant.now(), java.time.Instant.now());
        tripRepository.saveAndFlush(trip);

        pickupLocation = new GeoPoint(12.9716, 77.5946);

        nearDriver = new Driver(UUID.randomUUID(), "Near Driver", 4);
        nearDriver.updateLocation(new GeoPoint(12.9720, 77.5950));
        nearDriver.goOnline();
        driverRepository.saveAndFlush(nearDriver);

        farDriver = new Driver(UUID.randomUUID(), "Far Driver", 4);
        farDriver.updateLocation(new GeoPoint(13.0, 78.0));
        farDriver.goOnline();
        driverRepository.saveAndFlush(farDriver);
    }

    @Test
    void dispatchPrefersNearestEligibleDriver() {
        Optional<DriverOffer> offer = dispatchService.dispatchTrip(tripId, pickupLocation);

        assertThat(offer).isPresent();
        assertThat(offer.get().getDriverId()).isEqualTo(nearDriver.getId());
        assertThat(offer.get().getStatus()).isEqualTo(DriverOfferStatus.OFFERED);
    }

    @Test
    void rejectRoutesToNextDriver() {
        // First dispatch -> Near Driver
        Optional<DriverOffer> firstOffer = dispatchService.dispatchTrip(tripId, pickupLocation);
        assertThat(firstOffer).isPresent();
        assertThat(firstOffer.get().getDriverId()).isEqualTo(nearDriver.getId());

        // Reject
        dispatchService.rejectOffer(firstOffer.get().getId());

        // Second dispatch -> Near driver should be in cool-off, goes to Far Driver
        Optional<DriverOffer> secondOffer = dispatchService.dispatchTrip(tripId, pickupLocation);
        assertThat(secondOffer).isPresent();
        assertThat(secondOffer.get().getDriverId()).isEqualTo(farDriver.getId());
    }

    @Test
    void rejectedDriverExcludedInsideCoolOff() {
        Optional<DriverOffer> firstOffer = dispatchService.dispatchTrip(tripId, pickupLocation);
        dispatchService.rejectOffer(firstOffer.get().getId());

        // Turn offline the far driver to see if near driver is selected (should NOT be)
        farDriver.goOffline();
        driverRepository.saveAndFlush(farDriver);

        Optional<DriverOffer> secondOffer = dispatchService.dispatchTrip(tripId, pickupLocation);
        assertThat(secondOffer).isEmpty(); // Near driver in cool-off, Far driver offline -> nobody available
    }

    // Wait, testing "rejected driver becomes eligible after cool-off" is tricky 
    // with real time unless we inject a Clock or manipulate the DB.
    // Let's modify the DB record directly.
    @Test
    void rejectedDriverBecomesEligibleAfterCoolOff() {
        Optional<DriverOffer> firstOffer = dispatchService.dispatchTrip(tripId, pickupLocation);
        dispatchService.rejectOffer(firstOffer.get().getId());

        // Turn offline the far driver
        farDriver.goOffline();
        driverRepository.saveAndFlush(farDriver);

        // Manually move the rejection time back by 10 minutes
        DriverOffer offer = offerRepository.findById(firstOffer.get().getId()).orElseThrow();
        jdbcTemplate.update("UPDATE driver_offers SET created_at = created_at - interval '10 minutes' WHERE id = ?", offer.getId());

        Optional<DriverOffer> secondOffer = dispatchService.dispatchTrip(tripId, pickupLocation);
        assertThat(secondOffer).isPresent();
        assertThat(secondOffer.get().getDriverId()).isEqualTo(nearDriver.getId()); // Available again!
    }

    @Test
    void concurrentAcceptanceOnlyAllowsOneWinner() throws Exception {
        // Generate two offers for the same trip
        DriverOffer offer1 = new DriverOffer(UUID.randomUUID(), nearDriver.getId(), tripId);
        DriverOffer offer2 = new DriverOffer(UUID.randomUUID(), farDriver.getId(), tripId);
        offerRepository.save(offer1);
        offerRepository.save(offer2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        Callable<Void> acceptTask1 = () -> {
            latch.await();
            dispatchService.acceptOffer(offer1.getId());
            return null;
        };

        Callable<Void> acceptTask2 = () -> {
            latch.await();
            dispatchService.acceptOffer(offer2.getId());
            return null;
        };

        Future<Void> future1 = executor.submit(acceptTask1);
        Future<Void> future2 = executor.submit(acceptTask2);

        // Fire them simultaneously
        latch.countDown();

        int successCount = 0;
        int failureCount = 0;

        try {
            future1.get();
            successCount++;
        } catch (Exception e) {
            failureCount++;
            assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
        }

        try {
            future2.get();
            successCount++;
        } catch (Exception e) {
            failureCount++;
            assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
        }

        executor.shutdown();

        assertThat(successCount).isEqualTo(1);
        assertThat(failureCount).isEqualTo(1);

        // Verify state
        Trip trip = tripRepository.findById(tripId).orElseThrow();
        assertThat(trip.getStatus()).isEqualTo(com.ridebatching.trip.TripStatus.DISPATCHED);

        DriverOffer finalOffer1 = offerRepository.findById(offer1.getId()).orElseThrow();
        DriverOffer finalOffer2 = offerRepository.findById(offer2.getId()).orElseThrow();

        // One offer should be ACCEPTED, the other OFFERED (since acceptance failed)
        assertThat(
                (finalOffer1.getStatus() == DriverOfferStatus.ACCEPTED && finalOffer2.getStatus() == DriverOfferStatus.OFFERED) ||
                (finalOffer2.getStatus() == DriverOfferStatus.ACCEPTED && finalOffer1.getStatus() == DriverOfferStatus.OFFERED)
        ).isTrue();
    }
}
