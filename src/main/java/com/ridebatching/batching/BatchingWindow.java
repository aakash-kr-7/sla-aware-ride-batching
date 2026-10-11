package com.ridebatching.batching;

import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A time-bounded window representing a batching cycle.
 * Batching operates in discrete wall-clock windows based on UTC epoch seconds.
 * 
 * Invariants:
 * - Start is inclusive.
 * - End is exclusive.
 */
public record BatchingWindow(Instant start, Instant end) {

    public static BatchingWindow forTime(Instant time, Duration duration) {
        if (duration == null || duration.isNegative() || duration.getSeconds() < 1 || duration.getNano() != 0) {
            throw new IllegalArgumentException("Batching duration must be a positive whole number of seconds");
        }

        long durationSeconds = duration.getSeconds();
        long epochSeconds = time.getEpochSecond();
        
        long startSeconds = Math.floorDiv(epochSeconds, durationSeconds) * durationSeconds;
        Instant start = Instant.ofEpochSecond(startSeconds);
        Instant end = start.plus(duration);
        
        return new BatchingWindow(start, end);
    }

    public boolean contains(Instant time) {
        return !time.isBefore(start) && time.isBefore(end);
    }

    public List<Booking> selectEligible(List<Booking> candidates) {
        return candidates.stream()
                .filter(b -> b.getStatus() == BookingStatus.PENDING)
                .filter(b -> this.contains(b.getCreatedAt()))
                .toList();
    }
}
