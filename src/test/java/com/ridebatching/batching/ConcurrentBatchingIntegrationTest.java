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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentBatchingIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private BatchingService batchingService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private TripRepository tripRepository;

    private Employee testEmployee;



    @BeforeEach
    void setUp() {
        testEmployee = new Employee(UUID.randomUUID(), "Concurrent Test Employee", "test@example.com", "TestDept");
        employeeRepository.save(testEmployee);
    }



    @Test
    void shouldHandleConcurrentBatchingAttemptsWithoutDuplicates() throws Exception {
        // Given 10 pending bookings
        List<Booking> pendingBookings = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Booking booking = new Booking(
                    UUID.randomUUID(),
                    testEmployee,
                    new GeoPoint(0, 0),
                    new GeoPoint(0.01, 0.01),
                    Instant.now(),
                    BookingStatus.PENDING,
                    Instant.now().plusSeconds(3600), // generous SLA
                    Instant.now(),
                    UUID.randomUUID().toString()
            );
            pendingBookings.add(booking);
        }
        bookingRepository.saveAllAndFlush(pendingBookings);

        // When multiple batching workers trigger concurrently
        int workerCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        List<Callable<BatchingService.BatchingResult>> tasks = new ArrayList<>();

        for (int i = 0; i < workerCount; i++) {
            tasks.add(() -> batchingService.formBatches());
        }

        List<Future<BatchingService.BatchingResult>> results = executor.invokeAll(tasks);
        
        int totalBookingsConsidered = 0;
        for (Future<BatchingService.BatchingResult> result : results) {
            BatchingService.BatchingResult res = result.get();
            totalBookingsConsidered += res.bookingsConsidered();
        }

        executor.shutdown();

        // Then exactly 10 bookings were considered overall, meaning no worker batched the same booking twice
        // (Due to SKIP LOCKED, a row is either locked by one thread and skipped by others, 
        // or fetched by subsequent thread after being committed to CONFIRMED).
        assertThat(totalBookingsConsidered).isEqualTo(10);

        // Verify all bookings are assigned correctly
        List<Booking> allBookings = bookingRepository.findAll();
        for (Booking b : allBookings) {
            assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(b.getTripId()).isNotNull();
        }

        // Verify trips exist
        List<Trip> trips = tripRepository.findAll();
        assertThat(trips).isNotEmpty();
    }
}
