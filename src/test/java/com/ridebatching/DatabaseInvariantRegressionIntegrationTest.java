package com.ridebatching;

import com.ridebatching.booking.*;
import com.ridebatching.driver.*;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class DatabaseInvariantRegressionIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired BookingRepository bookings;
    @Autowired EmployeeRepository employees;
    @Autowired TripRepository trips;
    @Autowired DriverRepository drivers;
    @Autowired JdbcTemplate jdbc;
    private Booking booking;
    private Trip trip;
    private Driver driver;

    @BeforeEach void fixture() {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        Employee employee = employees.saveAndFlush(new Employee(id, id.toString(), "review", id + "@example.invalid"));
        booking = bookings.saveAndFlush(new Booking(UUID.randomUUID(), employee, new GeoPoint(0, 0), new GeoPoint(0, 0),
                now, BookingStatus.PENDING, now.plusSeconds(3600), now, UUID.randomUUID().toString()));
        trip = trips.saveAndFlush(new Trip(UUID.randomUUID(), now, now));
        driver = drivers.saveAndFlush(new Driver(UUID.randomUUID(), "review", 4));
    }
    @Test void dedupKeyCannotBeNullInDatabase() {
        assertThatThrownBy(() -> jdbc.update("UPDATE bookings SET deduplication_key=NULL WHERE id=?", booking.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void bookingStopCannotBelongToTwoTrips() {
        insertStop(trip.getId(), "PICKUP", 1);
        Trip other = trips.saveAndFlush(new Trip(UUID.randomUUID(), Instant.now(), Instant.now()));
        assertThatThrownBy(() -> insertStop(other.getId(), "PICKUP", 1)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void stopSequenceCannotBeAmbiguous() {
        insertStop(trip.getId(), "PICKUP", 1);
        assertThatThrownBy(() -> insertStop(trip.getId(), "DROPOFF", 1)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void offerCannotReferenceMissingDriver() {
        assertThatThrownBy(() -> insertOffer(UUID.randomUUID(), trip.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void offerCannotReferenceMissingTrip() {
        assertThatThrownBy(() -> insertOffer(driver.getId(), UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void driverCannotReferenceMissingActiveTrip() {
        assertThatThrownBy(() -> jdbc.update("UPDATE drivers SET active_trip_id=? WHERE id=?", UUID.randomUUID(), driver.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void tripCannotReferenceMissingDriver() {
        assertThatThrownBy(() -> jdbc.update("UPDATE trips SET driver_id=? WHERE id=?", UUID.randomUUID(), trip.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void persistedDriverCapacityMustBePositive() {
        assertThatThrownBy(() -> jdbc.update("UPDATE drivers SET vehicle_capacity=0 WHERE id=?", driver.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void staleDriverLocationUpdateCannotUndoStateTransition() {
        Driver state = drivers.findById(driver.getId()).orElseThrow();
        Driver location = drivers.findById(driver.getId()).orElseThrow();
        state.goOnline();
        drivers.saveAndFlush(state);
        location.updateLocation(new GeoPoint(1, 1));
        assertThatThrownBy(() -> drivers.saveAndFlush(location)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(drivers.findById(driver.getId()).orElseThrow().getStatus()).isEqualTo(DriverStatus.AVAILABLE);
        assertThat(drivers.findById(driver.getId()).orElseThrow().getLocation()).isEqualTo(new GeoPoint(0, 0));
    }
    private void insertStop(UUID tripId, String type, int sequence) {
        jdbc.update("INSERT INTO trip_stops (id,trip_id,booking_id,type,sequence_order,latitude,longitude) VALUES (?,?,?,?,?,0,0)",
                UUID.randomUUID(), tripId, booking.getId(), type, sequence);
    }
    private void insertOffer(UUID driverId, UUID tripId) {
        jdbc.update("INSERT INTO driver_offers (id,driver_id,trip_id,status,created_at,updated_at) VALUES (?,?,?,'OFFERED',now(),now())",
                UUID.randomUUID(), driverId, tripId);
    }
}
