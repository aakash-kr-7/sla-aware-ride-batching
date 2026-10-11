package com.ridebatching.booking;

import com.ridebatching.trip.Trip;
import com.ridebatching.trip.TripRepository;
import com.ridebatching.batching.BatchingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class BookingCancellationIntegrationTest extends com.ridebatching.AbstractPostgresIntegrationTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private BatchingService batchingService;

    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired private com.ridebatching.zone.ServiceableZoneRepository zones;
    @Autowired private com.ridebatching.dispatch.DispatchService dispatch;

    @Test
    void shouldCancelBookingAndReplanTripWhileLeavingOtherTripsUnchanged() {
        zones.saveAllAndFlush(List.of(
                new com.ridebatching.zone.ServiceableZone("First", 12.9716, 77.5946, 1000, true),
                new com.ridebatching.zone.ServiceableZone("Second", 13.0000, 77.6000, 1000, true)));
        // Create 2 employees for Trip A
        Employee emp1 = employeeRepository.save(new Employee(UUID.randomUUID(), "E1", "Alice", "a@test.com"));
        Employee emp2 = employeeRepository.save(new Employee(UUID.randomUUID(), "E2", "Bob", "b@test.com"));
        
        // Create 2 employees for Trip B (different pickup so they won't batch together)
        Employee emp3 = employeeRepository.save(new Employee(UUID.randomUUID(), "E3", "Charlie", "c@test.com"));
        Employee emp4 = employeeRepository.save(new Employee(UUID.randomUUID(), "E4", "Dave", "d@test.com"));

        // Zone 1
        Booking b1 = createTestBooking(emp1, 12.9716, 77.5946);
        Booking b2 = createTestBooking(emp2, 12.9720, 77.5950);

        // Zone 2 (far away)
        Booking b3 = createTestBooking(emp3, 13.0000, 77.6000);
        Booking b4 = createTestBooking(emp4, 13.0010, 77.6010);
        
        Employee emp5 = employeeRepository.save(new Employee(UUID.randomUUID(), "E5", "Eve", "e@test.com"));
        Booking b5 = createTestBooking(emp5, 13.0020, 77.6020);

        bookingRepository.saveAllAndFlush(List.of(b1, b2, b3, b4, b5));

        // Run batching
        BatchingService.BatchingResult res = batchingService.formBatches();
        assertTrue(res.tripsFormed() >= 2);

        UUID tripAId = bookingRepository.findById(b1.getId()).orElseThrow().getTripId();
        UUID tripBId = bookingRepository.findById(b3.getId()).orElseThrow().getTripId();
        var tripABefore = dispatch.getTrip(tripAId);
        var tripBBefore = dispatch.getTrip(tripBId);
        var otherUpdated = tripRepository.findById(tripBId).orElseThrow().getUpdatedAt();

        bookingService.cancelBooking(emp1.getId(), b1.getId());

        var tripAAfter = dispatch.getTrip(tripAId);
        assertEquals(tripABefore.stops().size() - 2, tripAAfter.stops().size());
        assertTrue(tripAAfter.stops().stream().noneMatch(stop -> stop.bookingId().equals(b1.getId())));
        assertEquals(tripBBefore, dispatch.getTrip(tripBId));
        assertEquals(otherUpdated, tripRepository.findById(tripBId).orElseThrow().getUpdatedAt());
    }
    
    private Booking createTestBooking(Employee emp, double lat, double lon) {
        java.time.Instant createdAt = java.time.Instant.now().minusSeconds(1200);
        return new Booking(
            UUID.randomUUID(),
            emp,
            new com.ridebatching.geo.GeoPoint(lat, lon),
            new com.ridebatching.geo.GeoPoint(12.9352, 77.6245),
            com.ridebatching.batching.BatchingWindow.forTime(createdAt, java.time.Duration.ofMinutes(15)).start(),
            BookingStatus.PENDING,
            java.time.Instant.now().plusSeconds(3600),
            createdAt,
            UUID.randomUUID().toString()
        );
    }
}
