package com.ridebatching.booking;

import com.ridebatching.trip.Trip;
import com.ridebatching.trip.TripRepository;
import com.ridebatching.batching.BatchingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
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

    @Test
    @Transactional
    void shouldCancelBookingAndReplanTripWhileLeavingOtherTripsUnchanged() {
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

        // Fetch bookings to get their trips
        List<Trip> trips = tripRepository.findAll();
        Trip tripABefore = trips.get(0);
        Trip tripBBefore = trips.get(1);
        UUID tripAId = tripABefore.getId();
        UUID tripBId = tripBBefore.getId();
        
        // Pick a booking from tripA to cancel
        UUID bookingToCancelId = tripABefore.getStops().get(0).getBookingId();
        Booking bookingToCancel = bookingRepository.findById(bookingToCancelId).orElseThrow();
        UUID empIdToCancel = bookingToCancel.getEmployee().getId();

        int tripBStopsCountBefore = tripBBefore.getStops().size();
        int tripAStopsCountBefore = tripABefore.getStops().size();

        // Cancel the booking
        bookingService.cancelBooking(empIdToCancel, bookingToCancelId);

        // Validate Trip A
        Trip tripAAfter = tripRepository.findById(tripAId).orElseThrow();
        assertEquals(tripAStopsCountBefore - 2, tripAAfter.getStops().size()); // Pickup and dropoff removed

        // Validate Trip B is unchanged
        Trip tripBAfter = tripRepository.findById(tripBId).orElseThrow();
        assertEquals(tripBStopsCountBefore, tripBAfter.getStops().size());
        assertEquals(tripBBefore.getUpdatedAt(), tripBAfter.getUpdatedAt()); // untouched
    }
    
    private Booking createTestBooking(Employee emp, double lat, double lon) {
        return new Booking(
            UUID.randomUUID(),
            emp,
            new com.ridebatching.geo.GeoPoint(lat, lon),
            new com.ridebatching.geo.GeoPoint(12.9352, 77.6245),
            java.time.Instant.now(),
            BookingStatus.PENDING,
            java.time.Instant.now().plusSeconds(3600),
            java.time.Instant.now(),
            UUID.randomUUID().toString()
        );
    }
}
