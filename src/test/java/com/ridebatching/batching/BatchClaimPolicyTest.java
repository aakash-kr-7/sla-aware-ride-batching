package com.ridebatching.batching;

import com.ridebatching.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BatchClaimPolicyTest {
    private final AppProperties properties = new AppProperties(
            new AppProperties.BatchingProperties(15, 45), new AppProperties.SlaProperties(20));

    @Test
    void rollsEligibilityForwardAtTheExactWindowBoundary() {
        BatchClaimPolicy policy = new BatchClaimPolicy(properties, 100, 300);
        Instant boundary = Instant.parse("2026-10-11T10:15:00Z");
        assertThat(policy.closedWindowsBefore(boundary.minusNanos(1)))
                .isEqualTo(Instant.parse("2026-10-11T10:00:00Z"));
        assertThat(policy.closedWindowsBefore(boundary)).isEqualTo(boundary);
        assertThat(policy.urgentDeadlineThrough(boundary)).isEqualTo(boundary.plusSeconds(300));
    }

    @Test
    void rejectsConfigurationThatWouldRemoveThePlanningBoundOrUrgencyGuard() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BatchClaimPolicy(properties, 0, 300));
        assertThatIllegalArgumentException().isThrownBy(() -> new BatchClaimPolicy(properties, 1001, 300));
        assertThatIllegalArgumentException().isThrownBy(() -> new BatchClaimPolicy(properties, 100, 0));
        AppProperties zeroWindow = new AppProperties(
                new AppProperties.BatchingProperties(0, 45), new AppProperties.SlaProperties(20));
        assertThatIllegalArgumentException().isThrownBy(() -> new BatchClaimPolicy(zeroWindow, 100, 300));
    }
}
