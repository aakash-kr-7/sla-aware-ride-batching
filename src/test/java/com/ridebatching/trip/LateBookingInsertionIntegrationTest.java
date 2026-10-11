package com.ridebatching.trip;

import com.ridebatching.AbstractPostgresIntegrationTest;

import com.ridebatching.batching.BatchingService;
import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingRepository;
import com.ridebatching.booking.BookingService;
import com.ridebatching.booking.EmployeeRepository;
import com.ridebatching.booking.Employee;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.booking.dto.BookingRequest;
import com.ridebatching.booking.dto.BookingResponse;
import com.ridebatching.booking.dto.GeoPointDto;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.TripRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

import org.testcontainers.junit.jupiter.Testcontainers;

import com.ridebatching.zone.ServiceableZone;
import com.ridebatching.zone.ServiceableZoneRepository;
import org.junit.jupiter.api.BeforeEach;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public class LateBookingInsertionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private BatchingService batchingService;

    @Autowired
    private TripRepository tripRepository;
    @Autowired private com.ridebatching.dispatch.DispatchService dispatch;
    
    @Autowired
    private ServiceableZoneRepository serviceableZoneRepository;

    @BeforeEach
    void setup() {
        bookingRepository.deleteAll();
        tripRepository.deleteAll();
        employeeRepository.deleteAll();
        serviceableZoneRepository.deleteAll();
        
        ServiceableZone zone = new ServiceableZone("HQ", 12.9716, 77.5946, 50000.0, true);
        serviceableZoneRepository.save(zone);
    }

    @Test
    void shouldInsertLateBookingIntoExistingTrip() {
        // Create 2 initial bookings in close proximity
        Employee emp1 = employeeRepository.save(new Employee(UUID.randomUUID(), "E1", "Alice", "a@test.com"));
        Employee emp2 = employeeRepository.save(new Employee(UUID.randomUUID(), "E2", "Bob", "b@test.com"));
        Employee emp3 = employeeRepository.save(new Employee(UUID.randomUUID(), "E3", "Charlie", "c@test.com"));

        Booking b1 = createTestBooking(emp1, 12.9716, 77.5946);
        Booking b2 = createTestBooking(emp2, 12.9720, 77.5950);

        bookingRepository.saveAllAndFlush(List.of(b1, b2));

        // Form batch
        BatchingService.BatchingResult result = batchingService.formBatches();
        assertEquals(2, result.bookingsConsidered());
        assertEquals(1, result.tripsFormed());
        
        // Ensure trip is created and active
        var trips = tripRepository.findAll();
        assertEquals(1, trips.size());
        UUID tripId = trips.get(0).getId();

        // Late booking arrives
        BookingResponse b3Response = bookingService.createBooking(emp3.getId(), new BookingRequest(new GeoPointDto(12.9718, 77.5948), new GeoPointDto(12.9352, 77.6245)));
        
        assertEquals(BookingStatus.CONFIRMED, b3Response.status());
        assertEquals(tripId, b3Response.tripId());
        
        var tripAfter = dispatch.getTrip(tripId);
        assertEquals(6, tripAfter.stops().size()); // 3 pickups, 3 dropoffs
    }
    
    @Test
    void shouldNotInsertLateBookingIfFarAway() {
        // Create 2 initial bookings in close proximity
        Employee emp1 = employeeRepository.save(new Employee(UUID.randomUUID(), "E4", "Dave", "d@test.com"));
        Employee emp2 = employeeRepository.save(new Employee(UUID.randomUUID(), "E5", "Eve", "e@test.com"));
        Employee emp3 = employeeRepository.save(new Employee(UUID.randomUUID(), "E6", "Frank", "f@test.com"));

        Booking b1 = createTestBooking(emp1, 12.9716, 77.5946);
        Booking b2 = createTestBooking(emp2, 12.9720, 77.5950);

        bookingRepository.saveAllAndFlush(List.of(b1, b2));

        // Form batch
        BatchingService.BatchingResult result = batchingService.formBatches();
        
        // Ensure trip is created and active
        var trips = tripRepository.findAll();
        
        // Late booking arrives in a totally different zone, impossible to insert
        BookingResponse b3Response = bookingService.createBooking(emp3.getId(), new BookingRequest(new GeoPointDto(12.6000, 77.5946), new GeoPointDto(12.9352, 77.6245)));
        
        assertEquals(BookingStatus.PENDING, b3Response.status());
        assertNull(b3Response.tripId());
    }

    private Booking createTestBooking(Employee emp, double lat, double lon) {
        Instant createdAt = Instant.now().minusSeconds(1200);
        return new Booking(
            UUID.randomUUID(),
            emp,
            new GeoPoint(lat, lon),
            new GeoPoint(12.9352, 77.6245),
            com.ridebatching.batching.BatchingWindow.forTime(createdAt, java.time.Duration.ofMinutes(15)).start(),
            BookingStatus.PENDING,
            Instant.now().plusSeconds(3600),
            createdAt,
            UUID.randomUUID().toString()
        );
    }
}
