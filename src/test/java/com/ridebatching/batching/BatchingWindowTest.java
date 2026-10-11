package com.ridebatching.batching;

import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.booking.Employee;
import com.ridebatching.geo.GeoPoint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BatchingWindowTest {

    private final Duration DURATION = Duration.ofMinutes(15);

    @Test
    void shouldRejectDurationsThatCannotBeAlignedToWholeEpochSeconds() {
        for (Duration duration : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(500), Duration.ofMillis(1500))) {
            assertThrows(IllegalArgumentException.class, () -> BatchingWindow.forTime(Instant.EPOCH, duration));
        }
    }

    @Test
    void shouldAlignToStartBoundary() {
        // Test exact start boundary (e.g. 10:15:00Z)
        Instant exactStart = ZonedDateTime.of(2026, 10, 10, 10, 15, 0, 0, ZoneOffset.UTC).toInstant();
        BatchingWindow window = BatchingWindow.forTime(exactStart, DURATION);

        assertEquals(exactStart, window.start());
        assertEquals(exactStart.plus(DURATION), window.end());
        assertTrue(window.contains(exactStart), "Window should include its exact start boundary");
    }

    @Test
    void shouldIncludeTimeJustBeforeEnd() {
        Instant start = ZonedDateTime.of(2026, 10, 10, 10, 15, 0, 0, ZoneOffset.UTC).toInstant();
        Instant justBeforeEnd = start.plus(DURATION).minusMillis(1);
        
        BatchingWindow window = BatchingWindow.forTime(justBeforeEnd, DURATION);

        assertEquals(start, window.start());
        assertTrue(window.contains(justBeforeEnd), "Window should include time just before end");
    }

    @Test
    void shouldExcludeExactEndAndRollToNextWindow() {
        Instant start = ZonedDateTime.of(2026, 10, 10, 10, 15, 0, 0, ZoneOffset.UTC).toInstant();
        Instant exactEnd = start.plus(DURATION); // 10:30:00Z
        
        BatchingWindow previousWindow = BatchingWindow.forTime(start, DURATION);
        assertFalse(previousWindow.contains(exactEnd), "Window should exclude its exact end boundary");

        BatchingWindow nextWindow = BatchingWindow.forTime(exactEnd, DURATION);
        assertEquals(exactEnd, nextWindow.start(), "Exact end should fall into the start of the next window");
    }

    @Test
    void shouldHandleCrossingMidnightProperly() {
        // 23:55Z should fall into 23:45-00:00 window, though 15 divides 60 evenly so 23:45 is a boundary.
        // Wait, for Duration of 15m, boundary before midnight is 23:45, next is 00:00.
        Instant lateNight = ZonedDateTime.of(2026, 10, 10, 23, 55, 0, 0, ZoneOffset.UTC).toInstant();
        BatchingWindow window = BatchingWindow.forTime(lateNight, DURATION);

        Instant expectedStart = ZonedDateTime.of(2026, 10, 10, 23, 45, 0, 0, ZoneOffset.UTC).toInstant();
        Instant expectedEnd = ZonedDateTime.of(2026, 10, 11, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();

        assertEquals(expectedStart, window.start());
        assertEquals(expectedEnd, window.end());
        assertTrue(window.contains(lateNight));
    }

    @Test
    void shouldEnforceUtcTimezonePolicy() {
        // The project dictates UTC epoch alignment for slots.
        // If we provide a time in +05:30 (IST), the epoch slotting should still align to UTC.
        // 10:10:00+05:30 is 04:40:00Z.
        // Slot should be 04:30:00Z to 04:45:00Z.
        ZonedDateTime istTime = ZonedDateTime.of(2026, 10, 10, 10, 10, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
        BatchingWindow window = BatchingWindow.forTime(istTime.toInstant(), DURATION);

        Instant expectedStartUtc = ZonedDateTime.of(2026, 10, 10, 4, 30, 0, 0, ZoneOffset.UTC).toInstant();
        assertEquals(expectedStartUtc, window.start(), "Window calculation must enforce UTC alignment regardless of local time origin");
    }

    @Test
    void shouldSelectEligiblePendingBookings() {
        Instant windowStart = ZonedDateTime.of(2026, 10, 10, 10, 15, 0, 0, ZoneOffset.UTC).toInstant();
        BatchingWindow window = new BatchingWindow(windowStart, windowStart.plus(DURATION));

        Booking eligible = createBooking(BookingStatus.PENDING, windowStart.plusSeconds(10));
        Booking outsideWindow = createBooking(BookingStatus.PENDING, windowStart.minusSeconds(10));
        Booking cancelled = createBooking(BookingStatus.CANCELLED, windowStart.plusSeconds(10));

        List<Booking> candidates = List.of(eligible, outsideWindow, cancelled);
        List<Booking> selected = window.selectEligible(candidates);

        assertEquals(1, selected.size());
        assertEquals(eligible.getId(), selected.get(0).getId());
    }

    private Booking createBooking(BookingStatus status, Instant createdAt) {
        Employee emp = new Employee(UUID.randomUUID(), "EMP", "Name", "email@example.com");
        return new Booking(
            UUID.randomUUID(),
            emp,
            new GeoPoint(12.0, 77.0),
            new GeoPoint(12.1, 77.1),
            createdAt, // In this test, slotTimestamp doesn't matter for selectEligible since we filter by createdAt
            status,
            createdAt.plusSeconds(1200),
            createdAt,
            UUID.randomUUID().toString()
        );
    }
}
