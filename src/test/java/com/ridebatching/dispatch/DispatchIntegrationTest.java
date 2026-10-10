package com.ridebatching.dispatch;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.driver.Driver;
import com.ridebatching.driver.DriverRepository;
import com.ridebatching.geo.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private DriverRepository driverRepository;

    @Autowired
    private DriverOfferRepository offerRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private UUID tripId;
    private GeoPoint pickupLocation;
    private Driver nearDriver;
    private Driver farDriver;

    @BeforeEach
    void setup() {
        tripId = UUID.randomUUID();
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
}
