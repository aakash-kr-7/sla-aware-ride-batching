package com.ridebatching.batching;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.booking.*;
import com.ridebatching.geo.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BatchClaimIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T10:17:00Z");
    @Autowired private BookingRepository bookings;
    @Autowired private EmployeeRepository employees;
    @Autowired private BatchClaimPolicy policy;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    private Employee employee;
    private long sequence;

    @BeforeEach
    void seedEmployee() {
        employee = employees.saveAndFlush(new Employee(UUID.randomUUID(), "CLAIM", "Claim test", "claim@example.invalid"));
    }

    @Test
    void claimsClosedWindowsAndUrgentOpenWindowsWithExactBoundaries() {
        Booking closed = pending("2026-10-11T10:14:59Z", NOW.plusSeconds(1000));
        Booking open = pending("2026-10-11T10:15:00Z", NOW.plusSeconds(1000));
        Booking urgent = pending("2026-10-11T10:16:00Z", NOW.plusSeconds(300));
        Booking notYetUrgent = pending("2026-10-11T10:16:00Z", NOW.plusSeconds(301));
        Booking expired = pending("2026-10-11T10:00:00Z", NOW.minusSeconds(1));
        Booking deadlineNow = pending("2026-10-11T10:16:00Z", NOW);
        Booking future = pending("2026-10-11T10:18:00Z", NOW.plusSeconds(100));
        Booking cancelled = pending("2026-10-11T10:00:00Z", NOW.plusSeconds(100));
        cancelled.cancel();
        bookings.saveAllAndFlush(List.of(closed, open, urgent, notYetUrgent, expired, deadlineNow, future, cancelled));

        assertThat(claim(100)).extracting(Booking::getId)
                .containsExactly(deadlineNow.getId(), urgent.getId(), closed.getId());
        assertThat(bookings.findById(expired.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void boundsClaimsAndBreaksEqualDeadlineTiesByCreationThenId() {
        Booking first = pending("2026-10-11T10:00:00Z", NOW.plusSeconds(1000));
        Booking later = pending("2026-10-11T10:01:00Z", NOW.plusSeconds(1000));
        Booking urgent = pending("2026-10-11T10:16:00Z", NOW.plusSeconds(20));
        Booking sameTime = pending("2026-10-11T10:00:00Z", NOW.plusSeconds(1000));
        bookings.saveAllAndFlush(List.of(later, first, sameTime, urgent));
        List<Booking> ties = java.util.stream.Stream.of(first, sameTime)
                .sorted(java.util.Comparator.comparing(Booking::getId)).toList();
        assertThat(claim(2)).extracting(Booking::getId).containsExactly(urgent.getId(), ties.get(0).getId());
        assertThat(claim(4)).extracting(Booking::getId)
                .containsExactly(urgent.getId(), ties.get(0).getId(), ties.get(1).getId(), later.getId());
    }

    private List<Booking> claim(int limit) {
        return new TransactionTemplate(transactions).execute(status -> bookings.claimEligibleBookings(
                NOW, policy.closedWindowsBefore(NOW), policy.urgentDeadlineThrough(NOW), limit));
    }

    private Booking pending(String createdAt, Instant deadline) {
        Instant created = Instant.parse(createdAt);
        UUID id = new UUID(0, ++sequence);
        return new Booking(id, employee, new GeoPoint(0, 0), new GeoPoint(0.01, 0),
                policy.windowFor(created).start(), BookingStatus.PENDING, deadline, created, id.toString());
    }
}
