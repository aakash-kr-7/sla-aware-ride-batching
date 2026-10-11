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

@org.springframework.boot.test.context.SpringBootTest(properties = "app.batching.max-bookings-per-cycle=3")
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
    @Autowired private com.ridebatching.zone.ServiceableZoneRepository zones;
    @org.springframework.boot.test.mock.mockito.SpyBean
    private BatchPlanner planner;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void overlappingBoundedWorkersClaimDisjointRowsAndBothMakeProgress() throws Exception {
        Instant now = Instant.now();
        Instant createdAt = now.minusSeconds(1200);
        Instant slot = BatchingWindow.forTime(createdAt, java.time.Duration.ofMinutes(15)).start();
        List<Booking> eligible = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            eligible.add(new Booking(new UUID(0, i + 1), testEmployee,
                    new GeoPoint(0, 0), new GeoPoint(0.01, 0), slot, BookingStatus.PENDING,
                    now.plusSeconds(3600 + i), createdAt, "overlap-" + i));
        }
        Booking collecting = new Booking(new UUID(0, 7), testEmployee, new GeoPoint(0, 0),
                new GeoPoint(0.01, 0), now, BookingStatus.PENDING, now.plusSeconds(3600), now, "collecting");
        bookingRepository.saveAllAndFlush(eligible);
        bookingRepository.saveAndFlush(collecting);
        var planning = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                planning.countDown();
                assertThat(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(planner).planBatch(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BatchingService.BatchingResult> first = executor.submit(batchingService::formBatches);
            assertThat(planning.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Future<BatchingService.BatchingResult> second = executor.submit(batchingService::formBatches);
            assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS).bookingsConsidered()).isEqualTo(3);
            // First worker still holds its three claims; the second has committed different rows.
            assertThat(bookingRepository.findById(eligible.get(0).getId()).orElseThrow().getStatus())
                    .isEqualTo(BookingStatus.PENDING);
            assertThat(bookingRepository.findById(eligible.get(3).getId()).orElseThrow().getStatus())
                    .isEqualTo(BookingStatus.CONFIRMED);
            assertThat(batchingService.formBatches().bookingsConsidered()).isZero();
            resume.countDown();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).bookingsConsidered()).isEqualTo(3);
            for (Booking booking : eligible) {
                Booking saved = bookingRepository.findById(booking.getId()).orElseThrow();
                assertThat(saved.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_stops WHERE booking_id=? AND trip_id=?",
                        Integer.class, saved.getId(), saved.getTripId())).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT count(DISTINCT trip_id) FROM trip_stops WHERE booking_id=?",
                        Integer.class, saved.getId())).isEqualTo(1);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_stops", Integer.class)).isEqualTo(12);
            assertThat(bookingRepository.findById(collecting.getId()).orElseThrow().getTripId()).isNull();
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void competingWorkerMustSkipRowsWhileFirstWorkerIsStillPlanning() throws Exception {
        Instant now = Instant.now();
        Instant createdAt = now.minusSeconds(1200);
        Instant slot = BatchingWindow.forTime(createdAt, java.time.Duration.ofMinutes(15)).start();
        Booking booking = bookingRepository.saveAndFlush(new Booking(UUID.randomUUID(), testEmployee,
                new GeoPoint(0, 0), new GeoPoint(0, 0), slot, BookingStatus.PENDING,
                now.plusSeconds(3600), createdAt, UUID.randomUUID().toString()));
        var planning = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            planning.countDown();
            assertThat(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(planner).planBatch(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BatchingService.BatchingResult> first = executor.submit(batchingService::formBatches);
            assertThat(planning.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Future<BatchingService.BatchingResult> second = executor.submit(batchingService::formBatches);
            assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS).bookingsConsidered()).isZero();
            resume.countDown();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).bookingsConsidered()).isEqualTo(1);
            Booking assigned = bookingRepository.findById(booking.getId()).orElseThrow();
            assertThat(assigned.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_stops WHERE booking_id=? AND trip_id=?", Integer.class,
                    assigned.getId(), assigned.getTripId())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT type) FROM trip_stops WHERE booking_id=?", Integer.class,
                    assigned.getId())).isEqualTo(2);
            assertThat(tripRepository.count()).isEqualTo(1);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }



    @BeforeEach
    void setUp() {
        testEmployee = new Employee(UUID.randomUUID(), "Concurrent Test Employee", "test@example.com", "TestDept");
        employeeRepository.save(testEmployee);
        zones.saveAndFlush(new com.ridebatching.zone.ServiceableZone("Concurrent", 0, 0, 5000, true));
    }



    @Test
    void shouldHandleConcurrentBatchingAttemptsWithoutDuplicates() throws Exception {
        // Given 10 pending bookings
        List<Booking> pendingBookings = new ArrayList<>();
        Instant now = Instant.now();
        Instant createdAt = now.minusSeconds(1200);
        Instant slot = BatchingWindow.forTime(createdAt, java.time.Duration.ofMinutes(15)).start();
        for (int i = 0; i < 10; i++) {
            Booking booking = new Booking(
                    UUID.randomUUID(),
                    testEmployee,
                    new GeoPoint(0, 0),
                    new GeoPoint(0.01, 0.01),
                    slot,
                    BookingStatus.PENDING,
                    now.plusSeconds(3600), // generous SLA
                    createdAt,
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
