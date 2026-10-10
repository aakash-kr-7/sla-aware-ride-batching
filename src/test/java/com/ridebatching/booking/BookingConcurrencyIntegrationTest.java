package com.ridebatching.booking;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.booking.dto.BookingRequest;
import com.ridebatching.booking.dto.BookingResponse;
import com.ridebatching.booking.dto.GeoPointDto;
import com.ridebatching.zone.ServiceableZone;
import com.ridebatching.zone.ServiceableZoneRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ServiceableZoneRepository zoneRepository;

    @Test
    void shouldHandleConcurrentIdenticalBookingRequests() throws Exception {
        // Setup initial data
        Employee employee = new Employee(UUID.randomUUID(), "EMP-CONC-1", "Concurrent", "conc@example.com");
        employeeRepository.saveAndFlush(employee);

        ServiceableZone zone = new ServiceableZone("Tech Park Conc", 12.0, 77.0, 5000, true);
        zoneRepository.saveAndFlush(zone);

        BookingRequest request = new BookingRequest(
            new GeoPointDto(12.001, 77.001), // Inside zone
            new GeoPointDto(12.9352, 77.6245)
        );

        int numberOfThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Callable<BookingResponse>> tasks = new ArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            tasks.add(() -> {
                latch.await(); // Synchronize all threads to start exactly at the same time
                return bookingService.createBooking(employee.getId(), request);
            });
        }

        long initialCount = bookingRepository.count();

        // Submit all tasks
        List<Future<BookingResponse>> futures = new ArrayList<>();
        for (Callable<BookingResponse> task : tasks) {
            futures.add(executor.submit(task));
        }

        // Release the latch to let them run concurrently
        latch.countDown();

        List<BookingResponse> responses = new ArrayList<>();
        for (Future<BookingResponse> future : futures) {
            responses.add(future.get()); // Wait and get result
        }

        executor.shutdown();

        // Assert exactly one new booking row exists
        assertEquals(initialCount + 1, bookingRepository.count());

        // Assert all callers receive the same logical booking outcome (same UUID)
        UUID expectedBookingId = responses.get(0).id();
        for (BookingResponse response : responses) {
            assertEquals(expectedBookingId, response.id(), "All concurrent requests should return the same booking ID");
        }
    }
}
