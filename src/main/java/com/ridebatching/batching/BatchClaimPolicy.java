package com.ridebatching.batching;

import com.ridebatching.config.AppProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Collection closes at UTC bucket boundaries; urgent bookings may leave the bucket early. */
@Component
public class BatchClaimPolicy {
    private final Duration windowDuration;
    private final Duration urgentLeadTime;
    private final int maxBookingsPerCycle;

    public BatchClaimPolicy(AppProperties properties,
                           @Value("${app.batching.max-bookings-per-cycle:100}") int maxBookingsPerCycle,
                           @Value("${app.batching.urgent-lead-time-seconds:300}") long urgentLeadTimeSeconds) {
        windowDuration = Duration.ofMinutes(properties.batching().windowDurationMinutes());
        BatchingWindow.forTime(Instant.EPOCH, windowDuration);
        if (maxBookingsPerCycle < 1 || maxBookingsPerCycle > 1000) {
            throw new IllegalArgumentException("Batch claim limit must be between 1 and 1000");
        }
        if (urgentLeadTimeSeconds < 1) {
            throw new IllegalArgumentException("Urgent lead time must be positive");
        }
        this.maxBookingsPerCycle = maxBookingsPerCycle;
        urgentLeadTime = Duration.ofSeconds(urgentLeadTimeSeconds);
    }

    public Instant closedWindowsBefore(Instant now) {
        return windowFor(now).start();
    }

    public Instant urgentDeadlineThrough(Instant now) {
        return now.plus(urgentLeadTime);
    }

    public BatchingWindow windowFor(Instant createdAt) {
        return BatchingWindow.forTime(createdAt, windowDuration);
    }

    public int maxBookingsPerCycle() {
        return maxBookingsPerCycle;
    }
}
