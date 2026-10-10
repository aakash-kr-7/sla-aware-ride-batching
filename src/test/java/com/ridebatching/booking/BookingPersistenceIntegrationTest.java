package com.ridebatching.booking;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.geo.GeoPoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BookingPersistenceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void shouldSaveValidBooking() {
        Employee employee = new Employee(UUID.randomUUID(), "EMP-100", "Alice", "alice@example.com");
        employeeRepository.saveAndFlush(employee);

        GeoPoint pickup = new GeoPoint(12.9716, 77.5946);
        GeoPoint dropoff = new GeoPoint(12.9352, 77.6245);
        Instant now = Instant.now();

        Booking booking = new Booking(
                UUID.randomUUID(),
                employee,
                pickup,
                dropoff,
                now,
                BookingStatus.PENDING,
                now.plusSeconds(20 * 60),
                now
        );

        Booking saved = bookingRepository.saveAndFlush(booking);
        assertNotNull(saved.getId());
    }

    @Test
    void shouldEnforceDeduplicationConstraint() {
        Employee employee = new Employee(UUID.randomUUID(), "EMP-101", "Bob", "bob@example.com");
        employeeRepository.saveAndFlush(employee);

        GeoPoint pickup = new GeoPoint(12.971612, 77.594634); // Note 6 decimal precision
        GeoPoint dropoff = new GeoPoint(12.9352, 77.6245);
        Instant slot = Instant.parse("2026-10-10T08:00:00Z");

        Booking booking1 = new Booking(
                UUID.randomUUID(),
                employee,
                pickup,
                dropoff,
                slot,
                BookingStatus.PENDING,
                slot.plusSeconds(20 * 60),
                Instant.now()
        );
        bookingRepository.saveAndFlush(booking1);

        // Slightly different GPS coordinates that round to the same 4 decimal places
        GeoPoint duplicatedPickup = new GeoPoint(12.971649, 77.594601);
        
        Booking booking2 = new Booking(
                UUID.randomUUID(),
                employee,
                duplicatedPickup,
                dropoff,
                slot,
                BookingStatus.PENDING,
                slot.plusSeconds(20 * 60),
                Instant.now()
        );

        // Should throw unique constraint violation due to idx_bookings_dedup
        assertThrows(DataIntegrityViolationException.class, () -> bookingRepository.saveAndFlush(booking2));
    }
}
