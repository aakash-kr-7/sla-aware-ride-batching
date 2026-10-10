package com.ridebatching.trip;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.batching.BatchingService;
import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingRepository;
import com.ridebatching.booking.BookingService;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.booking.Employee;
import com.ridebatching.booking.EmployeeRepository;
import com.ridebatching.booking.dto.BookingRequest;
import com.ridebatching.booking.dto.GeoPointDto;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.zone.ServiceableZone;
import com.ridebatching.zone.ServiceableZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@org.springframework.boot.test.context.SpringBootTest
class TripMutationConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BatchingService batchingService;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ServiceableZoneRepository zoneRepository;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @BeforeEach
    void setup() {
        bookingRepository.deleteAll();
        tripRepository.deleteAll();
        employeeRepository.deleteAll();
        zoneRepository.deleteAll();

        ServiceableZone zone = new ServiceableZone("Tech Park Conc", 12.0, 77.0, 50000.0, true);
        zoneRepository.saveAndFlush(zone);
    }

    @Test
    void shouldHandleConcurrentLateInsertionsWithoutExceedingCapacity() throws Exception {
        // Create an initial trip with 3 out of 4 seats filled
        Employee e1 = employeeRepository.save(new Employee(UUID.randomUUID(), "E1", "Alice", "a@t.com"));
        Employee e2 = employeeRepository.save(new Employee(UUID.randomUUID(), "E2", "Bob", "b@t.com"));
        Employee e3 = employeeRepository.save(new Employee(UUID.randomUUID(), "E3", "Charlie", "c@t.com"));

        Booking b1 = createTestBooking(e1, 12.001, 77.001);
        Booking b2 = createTestBooking(e2, 12.002, 77.002);
        Booking b3 = createTestBooking(e3, 12.003, 77.003);
        bookingRepository.saveAllAndFlush(List.of(b1, b2, b3));

        batchingService.formBatches();

        List<Trip> trips = tripRepository.findAll();
        assertEquals(1, trips.size());
        Trip trip = tripRepository.findById(trips.get(0).getId()).orElseThrow();
        // Since we don't have a transaction, we should fetch stops if they are lazy, but calling getStops() causes exception.
        // Let's use a programmatic transaction for the checks
        Integer stopCount = transactionTemplate.execute(status -> tripRepository.findById(trip.getId()).orElseThrow().getStops().size());
        assertEquals(3, stopCount / 2); // 3 pickups, 3 dropoffs

        // We now have 1 seat remaining. Try to insert 5 concurrent late bookings.
        int numThreads = 5;
        List<Employee> lateEmployees = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            lateEmployees.add(employeeRepository.save(new Employee(UUID.randomUUID(), "LATE-" + i, "Late " + i, i + "@t.com")));
        }

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            Employee lateEmp = lateEmployees.get(i);
            tasks.add(() -> {
                latch.await();
                BookingRequest req = new BookingRequest(new GeoPointDto(12.0015, 77.0015), new GeoPointDto(12.9352, 77.6245));
                var resp = bookingService.createBooking(lateEmp.getId(), req);
                return resp.status() == BookingStatus.CONFIRMED;
            });
        }

        List<Future<Boolean>> futures = new ArrayList<>();
        for (Callable<Boolean> task : tasks) {
            futures.add(executor.submit(task));
        }

        latch.countDown(); // start all

        int successCount = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                successCount++;
            }
        }
        executor.shutdown();

        // Exactly ONE should succeed because capacity is 4.
        assertEquals(1, successCount, "Only one concurrent late insertion should succeed due to capacity");
        
        Integer finalStopCount = transactionTemplate.execute(status -> tripRepository.findById(trip.getId()).orElseThrow().getStops().size());
        assertEquals(4, finalStopCount / 2, "Trip should exactly have 4 passengers");
    }

    private Booking createTestBooking(Employee emp, double lat, double lon) {
        return new Booking(
            UUID.randomUUID(),
            emp,
            new GeoPoint(lat, lon),
            new GeoPoint(12.9352, 77.6245),
            Instant.now(),
            BookingStatus.PENDING,
            Instant.now().plusSeconds(3600),
            Instant.now(),
            UUID.randomUUID().toString()
        );
    }
}
