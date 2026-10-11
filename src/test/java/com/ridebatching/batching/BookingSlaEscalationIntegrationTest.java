package com.ridebatching.batching;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.booking.*;
import com.ridebatching.escalation.*;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.TripRepository;
import com.ridebatching.zone.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "app.batching.max-bookings-per-cycle=2")
class BookingSlaEscalationIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T10:17:00Z");
    @Autowired BatchingService batching;
    @Autowired BookingRepository bookings;
    @Autowired EmployeeRepository employees;
    @Autowired ServiceableZoneRepository zones;
    @Autowired TripRepository trips;
    @Autowired SlaEscalationRepository issues;
    @Autowired BookingSlaEscalations escalations;
    @Autowired TransactionTemplate transaction;
    @MockBean(name = "batchingClock") Clock clock;
    private Employee employee;

    @BeforeEach void seed() {
        when(clock.instant()).thenReturn(NOW);
        employee = employees.saveAndFlush(new Employee(UUID.randomUUID(), "SLA", "Rider", "sla@example.invalid"));
        zones.saveAndFlush(new ServiceableZone("SLA zone", 0, 0, 5000, true));
    }

    @Test void soloMissPersistsOneOpenIssueAcrossRepeatedBatchingAndKeepsBookingPending() {
        Booking missed = booking(-1);
        assertThat(batching.formBatches()).isEqualTo(new BatchingService.BatchingResult(0, 0, 0));
        SlaEscalation first = issues.findByStatus(SlaEscalationStatus.OPEN).get(0);
        assertThat(first.getReferenceId()).isEqualTo(missed.getId());
        assertThat(first.getReason()).isEqualTo(BookingSlaEscalations.SOLO_UNREACHABLE);
        for (int i = 0; i < 3; i++) batching.formBatches();
        assertThat(issues.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getId()).isEqualTo(first.getId());
            assertThat(e.getCreatedAt()).isEqualTo(first.getCreatedAt());
            assertThat(e.getUpdatedAt()).isEqualTo(first.getUpdatedAt());
        });
        assertThat(bookings.findById(missed.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(trips.count()).isZero();
    }

    @Test void recoveryIsBoundedDoesNotStarveValidClaimsAndDeadlineEqualityStillPasses() {
        booking(-3); booking(-2); booking(-1);
        Booking equality = booking(0);
        assertThat(batching.formBatches().tripsFormed()).isEqualTo(1);
        assertThat(issues.count()).isEqualTo(2);
        assertThat(bookings.findById(equality.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        batching.formBatches(); batching.formBatches();
        assertThat(issues.count()).isEqualTo(3);
    }

    @Test void competingIssueWritersAreIdempotentThroughThePostgresUniqueIndex() throws Exception {
        UUID bookingId = booking(-1).getId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Void> open = () -> {
                start.await();
                transaction.executeWithoutResult(s -> escalations.soloUnreachable(bookingId, NOW));
                return null;
            };
            Future<Void> first = pool.submit(open), second = pool.submit(open);
            start.countDown(); first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertThat(issues.count()).isEqualTo(1);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void resolvingWithoutRemedyRetainsHistoryAndAllowsOneNewOpenIssue() {
        booking(-1); batching.formBatches();
        UUID id = issues.findAll().get(0).getId();
        transaction.executeWithoutResult(s -> issues.findById(id).orElseThrow().resolve());
        batching.formBatches(); batching.formBatches();
        assertThat(issues.count()).isEqualTo(2);
        assertThat(issues.findByStatus(SlaEscalationStatus.OPEN)).hasSize(1);
        assertThat(issues.findByStatus(SlaEscalationStatus.RESOLVED)).hasSize(1);
    }

    private Booking booking(long remaining) {
        Instant created = NOW.minusSeconds(1200);
        return bookings.saveAndFlush(new Booking(UUID.randomUUID(), employee, new GeoPoint(0, 0), new GeoPoint(0.01, 0),
                BatchingWindow.forTime(created, Duration.ofMinutes(15)).start(), BookingStatus.PENDING,
                NOW.plusSeconds(remaining), created, UUID.randomUUID().toString()));
    }
}
