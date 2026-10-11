package com.ridebatching.dispatch;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.booking.*;
import com.ridebatching.driver.*;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import static org.assertj.core.api.Assertions.*;

class DispatchMutationRegressionIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired DispatchService service;
    @Autowired DriverRepository drivers;
    @Autowired DriverOfferRepository offers;
    @Autowired TripRepository trips;
    @Autowired BookingRepository bookings;
    @Autowired EmployeeRepository employees;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired RejectionCoolOffPolicy coolOff;

    @Test
    void coolOffExpiresAtExactRejectionTimeBoundary() {
        Driver driver = driver(4, 0);
        Trip trip = trip(1);
        DriverOffer offer = offer(driver, trip);
        service.rejectOffer(offer.getId());
        Instant now = Instant.parse("2026-10-11T12:00:00Z");
        jdbc.update("UPDATE driver_offers SET updated_at=? WHERE id=?", java.sql.Timestamp.from(now.minusSeconds(300)), offer.getId());
        assertThat(coolOff.isExcludedByCoolOff(driver.getId(), trip.getId(), now)).isFalse();
        jdbc.update("UPDATE driver_offers SET updated_at=? WHERE id=?", java.sql.Timestamp.from(now.minusSeconds(299)), offer.getId());
        assertThat(coolOff.isExcludedByCoolOff(driver.getId(), trip.getId(), now)).isTrue();
    }

    @Test
    void capacityCountsConcurrentPassengersRatherThanTotalBookings() {
        Driver driver = driver(1, 0);
        Trip trip = trip(4);
        transaction.executeWithoutResult(status -> {
            Trip locked = trips.findByIdForUpdate(trip.getId()).orElseThrow();
            List<TripStop> old = List.copyOf(locked.getStops());
            List<TripStop> turnover = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                turnover.add(old.get(i));
                turnover.add(old.get(i + 4));
            }
            locked.assignRoute(turnover);
        });
        DriverOffer offer = service.dispatchTrip(trip.getId(), new GeoPoint(0, 0)).orElseThrow();
        service.acceptOffer(offer.getId());
        assertThat(drivers.findById(driver.getId()).orElseThrow().getActiveTripId()).isEqualTo(trip.getId());
    }

    @Test
    void sameDriverCannotAcceptDifferentTripsConcurrently() throws Exception {
        Driver driver = driver(4, 0);
        Trip first = trip(1), second = trip(1);
        DriverOffer a = offer(driver, first), b = offer(driver, second);
        List<Throwable> outcomes = competeWhileRowLocked("drivers", driver.getId(),
                () -> service.acceptOffer(a.getId()), () -> service.acceptOffer(b.getId()));
        assertOneConflict(outcomes);
        UUID active = drivers.findById(driver.getId()).orElseThrow().getActiveTripId();
        assertThat(List.of(first.getId(), second.getId())).contains(active);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trips WHERE status='DISPATCHED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM driver_offers WHERE status='ACCEPTED' AND trip_id=?", Integer.class, active)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM driver_offers WHERE status='OFFERED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void acceptanceAndRejectionCannotBothSucceed() throws Exception {
        Driver driver = driver(4, 0);
        Trip trip = trip(1);
        DriverOffer offer = offer(driver, trip);
        List<Throwable> outcomes = competeWhileRowLocked("trips", trip.getId(),
                () -> service.acceptOffer(offer.getId()), () -> service.rejectOffer(offer.getId()));
        assertOneConflict(outcomes);
        DriverOffer current = offers.findById(offer.getId()).orElseThrow();
        Trip currentTrip = trips.findById(trip.getId()).orElseThrow();
        Driver currentDriver = drivers.findById(driver.getId()).orElseThrow();
        if (current.getStatus() == DriverOfferStatus.ACCEPTED) {
            assertThat(currentTrip.getStatus()).isEqualTo(TripStatus.DISPATCHED);
            assertThat(currentDriver.getActiveTripId()).isEqualTo(trip.getId());
        } else {
            assertThat(current.getStatus()).isEqualTo(DriverOfferStatus.REJECTED);
            assertThat(currentTrip.getStatus()).isEqualTo(TripStatus.PLANNED);
            assertThat(currentDriver.getActiveTripId()).isNull();
        }
    }

    @Test
    void selectionMustRespectActualVehicleCapacity() {
        Driver small = driver(2, 0);
        Driver large = driver(4, .001);
        Trip trip = trip(4);
        assertThat(service.dispatchTrip(trip.getId(), new GeoPoint(0, 0)).orElseThrow().getDriverId())
                .isEqualTo(large.getId()).isNotEqualTo(small.getId());
    }

    @Test
    void acceptanceMustRecheckCapacityAndRollBackOffer() {
        Driver small = driver(2, 0);
        Trip trip = trip(4);
        DriverOffer offer = offer(small, trip);
        assertThatThrownBy(() -> service.acceptOffer(offer.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(offers.findById(offer.getId()).orElseThrow().getStatus()).isEqualTo(DriverOfferStatus.OFFERED);
        assertThat(trips.findById(trip.getId()).orElseThrow().getStatus()).isEqualTo(TripStatus.PLANNED);
        assertThat(drivers.findById(small.getId()).orElseThrow().getActiveTripId()).isNull();
    }

    @Test
    void rejectionOfOldOfferStartsCoolOffAtResponseTime() {
        Driver driver = driver(4, 0);
        Trip trip = trip(1);
        DriverOffer offer = offer(driver, trip);
        jdbc.update("UPDATE driver_offers SET created_at=created_at-interval '10 minutes' WHERE id=?", offer.getId());
        service.rejectOffer(offer.getId());
        assertThat(service.dispatchTrip(trip.getId(), new GeoPoint(0, 0))).isEmpty();
    }

    @Test
    void dispatchMustRejectCancelledTrip() {
        driver(4, 0);
        Trip trip = trip(1);
        jdbc.update("UPDATE trips SET status='CANCELLED' WHERE id=?", trip.getId());
        assertThatThrownBy(() -> service.dispatchTrip(trip.getId(), new GeoPoint(0, 0))).isInstanceOf(IllegalStateException.class);
        assertThat(offers.count()).isZero();
    }

    private List<Throwable> competeWhileRowLocked(String table, UUID id, Runnable a, Runnable b) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> futures = transaction.execute(status -> {
                // The table names are fixed test constants. Hold a real row lock until both workers wait in PostgreSQL.
                jdbc.queryForObject("SELECT id FROM " + table + " WHERE id=? FOR UPDATE", UUID.class, id);
                List<Future<Throwable>> tasks = List.of(executor.submit(() -> invoke("dispatch-review-a", a)),
                        executor.submit(() -> invoke("dispatch-review-b", b)));
                awaitBlocked("dispatch-review-a");
                awaitBlocked("dispatch-review-b");
                return tasks;
            });
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> future : futures) outcomes.add(future.get(10, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
    private Throwable invoke(String name, Runnable action) {
        try {
            transaction.executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL application_name='" + name + "'");
                action.run();
            });
            return null;
        } catch (RuntimeException failure) { return failure; }
    }
    private void awaitBlocked(String name) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock')", Boolean.class, name))) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        fail("Worker did not enter an actual PostgreSQL lock wait: " + name);
    }
    private void assertOneConflict(List<Throwable> outcomes) {
        assertThat(outcomes.stream().filter(Objects::isNull).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(Objects::nonNull).toList()).singleElement().isInstanceOf(IllegalStateException.class);
    }
    private Driver driver(int capacity, double latitude) {
        Driver driver = new Driver(UUID.randomUUID(), "review", capacity);
        driver.updateLocation(new GeoPoint(latitude, 0));
        driver.goOnline();
        return drivers.saveAndFlush(driver);
    }
    private DriverOffer offer(Driver driver, Trip trip) {
        return offers.saveAndFlush(new DriverOffer(UUID.randomUUID(), driver.getId(), trip.getId()));
    }
    private Trip trip(int riders) {
        Instant now = Instant.now();
        Trip trip = new Trip(UUID.randomUUID(), now, now);
        List<TripStop> pickups = new ArrayList<>(), drops = new ArrayList<>();
        for (int i = 0; i < riders; i++) {
            UUID employeeId = UUID.randomUUID();
            Employee employee = employees.saveAndFlush(new Employee(employeeId, employeeId.toString(), "review", employeeId + "@example.invalid"));
            Booking booking = bookings.saveAndFlush(new Booking(UUID.randomUUID(), employee, new GeoPoint(0, 0), new GeoPoint(.02, 0),
                    now, BookingStatus.PENDING, now.plusSeconds(3600), now, UUID.randomUUID().toString()));
            pickups.add(new TripStop(UUID.randomUUID(), booking.getId(), TripStopType.PICKUP, new GeoPoint(0, 0)));
            drops.add(new TripStop(UUID.randomUUID(), booking.getId(), TripStopType.DROPOFF, booking.getDropoffLocation()));
        }
        pickups.addAll(drops);
        trip.assignRoute(pickups);
        Trip saved = trips.saveAndFlush(trip);
        for (TripStop stop : saved.getStops()) if (stop.getType() == TripStopType.PICKUP) {
            Booking b = bookings.findById(stop.getBookingId()).orElseThrow();
            b.setTripId(saved.getId()); b.confirm(); bookings.saveAndFlush(b);
        }
        return saved;
    }
}
