package com.ridebatching.batching;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingRepository;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.booking.Employee;
import com.ridebatching.booking.EmployeeRepository;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.Trip;
import com.ridebatching.trip.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BatchingRollbackIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private BatchingService batchingService;

    @Autowired
    private BookingRepository bookingRepository;

    @SpyBean
    private TripRepository tripRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    private UUID employeeId = UUID.randomUUID();
    @Autowired private com.ridebatching.zone.ServiceableZoneRepository zones;

    @BeforeEach
    void setup() {
        Employee emp = new Employee(employeeId, "EMP_R", "Rollback", "rb@test.com");
        employeeRepository.save(emp);
        zones.saveAndFlush(new com.ridebatching.zone.ServiceableZone("Rollback", 0, 0, 5000, true));
    }

    @Test
    void batchFormationRollsBackOnFailureAndAllowsLaterRetry() {
        Employee emp = employeeRepository.findById(employeeId).orElseThrow();
        Instant createdAt = Instant.now().minusSeconds(1200); // A closed collection window.
        Instant slot = BatchingWindow.forTime(createdAt, java.time.Duration.ofMinutes(15)).start();
        Booking booking1 = new Booking(UUID.randomUUID(), emp, new GeoPoint(0,0), new GeoPoint(1,1), slot, BookingStatus.PENDING, Instant.now().plusSeconds(3600), createdAt, "dedup_r1");
        Booking booking2 = new Booking(UUID.randomUUID(), emp, new GeoPoint(0,0), new GeoPoint(1,1), slot, BookingStatus.PENDING, Instant.now().plusSeconds(3600), createdAt, "dedup_r2");
        bookingRepository.saveAll(List.of(booking1, booking2));

        // Inject controlled failure during saveAll for trips
        Mockito.doThrow(new RuntimeException("Injected DB failure during trip creation"))
               .when(tripRepository).saveAll(Mockito.anyIterable());

        // 1. Attempt batching -> should throw and rollback
        assertThrows(RuntimeException.class, () -> batchingService.formBatches());

        // 2. Assert no partial trips survive
        List<Trip> allTrips = tripRepository.findAll();
        assertThat(allTrips).isEmpty();

        // 3. Assert bookings remain pending and have no trip assigned (not half-associated)
        List<Booking> allBookings = bookingRepository.findAll();
        assertThat(allBookings).hasSize(2);
        for (Booking b : allBookings) {
            assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING);
            assertThat(b.getTripId()).isNull();
        }

        // 4. Remove injected failure
        Mockito.reset(tripRepository);

        // 5. Assert later valid batching attempt proceeds correctly
        BatchingService.BatchingResult result = batchingService.formBatches();
        
        assertThat(result.tripsFormed()).isGreaterThan(0);
        
        List<Trip> successfulTrips = tripRepository.findAll();
        assertThat(successfulTrips).isNotEmpty();

        List<Booking> processedBookings = bookingRepository.findAll();
        for (Booking b : processedBookings) {
            assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(b.getTripId()).isNotNull();
        }
    }
}
