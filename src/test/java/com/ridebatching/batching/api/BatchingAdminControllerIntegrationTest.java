package com.ridebatching.batching.api;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.batching.BatchingService;
import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingRepository;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.booking.Employee;
import com.ridebatching.booking.EmployeeRepository;
import com.ridebatching.geo.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.boot.test.context.SpringBootTest(webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchingAdminControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    private Employee testEmployee;

    @Autowired
    private com.ridebatching.auth.UserAccountRepository userAccountRepository;

    @Autowired
    private com.ridebatching.auth.JwtService jwtService;

    @BeforeEach
    void setUp() {
        testEmployee = new Employee(UUID.randomUUID(), "EMP-ADMIN-TEST", "Admin Test Employee", "admin@example.com");
        employeeRepository.save(testEmployee);
        userAccountRepository.save(new com.ridebatching.auth.UserAccount(UUID.randomUUID(),
                "test-admin", "unused-test-password-hash", com.ridebatching.auth.Role.ADMIN, null));
    }





    @Test
    void shouldTriggerBatchingAndReturnResult() {
        // Given a pending booking
        Booking booking = new Booking(
                UUID.randomUUID(),
                testEmployee,
                new GeoPoint(0, 0),
                new GeoPoint(0.01, 0.01),
                Instant.now(),
                BookingStatus.PENDING,
                Instant.now().plusSeconds(3600),
                Instant.now(),
                UUID.randomUUID().toString()
        );
        bookingRepository.saveAndFlush(booking);

        // When triggering the admin endpoint
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(jwtService.generateToken("test-admin", com.ridebatching.auth.Role.ADMIN));
        ResponseEntity<BatchingService.BatchingResult> response = restTemplate.postForEntity(
                "/api/admin/batching/trigger", new org.springframework.http.HttpEntity<>(headers),
                BatchingService.BatchingResult.class);

        // Then it succeeds and reports the batched booking
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BatchingService.BatchingResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.bookingsConsidered()).isEqualTo(1);
        assertThat(result.tripsFormed()).isEqualTo(1);
        assertThat(result.bookingsLeftPending()).isEqualTo(0);

        // And the database state is updated
        Booking updatedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(updatedBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(updatedBooking.getTripId()).isNotNull();
    }
}
