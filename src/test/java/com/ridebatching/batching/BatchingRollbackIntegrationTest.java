package com.ridebatching.batching;

import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingRepository;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.booking.Employee;
import com.ridebatching.booking.EmployeeRepository;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.Trip;
import com.ridebatching.trip.TripRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Testcontainers
class BatchingRollbackIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private BatchingService batchingService;

    @Autowired
    private BookingRepository bookingRepository;

    @SpyBean
    private TripRepository tripRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    private UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        bookingRepository.deleteAll();
        tripRepository.deleteAll();
        employeeRepository.deleteAll();

        Employee emp = new Employee(employeeId, "EMP_R", "Rollback", "rb@test.com");
        employeeRepository.save(emp);
    }

    @AfterEach
    void tearDown() {
        bookingRepository.deleteAll();
        tripRepository.deleteAll();
        employeeRepository.deleteAll();
    }

    @Test
    void batchFormationRollsBackOnFailureAndAllowsLaterRetry() {
        Employee emp = employeeRepository.findById(employeeId).orElseThrow();
        Booking booking1 = new Booking(UUID.randomUUID(), emp, new GeoPoint(0,0), new GeoPoint(1,1), Instant.now(), BookingStatus.PENDING, Instant.now().plusSeconds(3600), Instant.now(), "dedup_r1");
        Booking booking2 = new Booking(UUID.randomUUID(), emp, new GeoPoint(0,0), new GeoPoint(1,1), Instant.now(), BookingStatus.PENDING, Instant.now().plusSeconds(3600), Instant.now(), "dedup_r2");
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
        Mockito.doCallRealMethod().when(tripRepository).saveAll(Mockito.anyIterable());

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
