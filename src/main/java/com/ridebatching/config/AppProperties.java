package com.ridebatching.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared window, direction and pickup SLA settings consumed by booking and batching.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    BatchingProperties batching,
    SlaProperties sla
) {
    public record BatchingProperties(int windowDurationMinutes, int maxBearingAngleDiffDegrees) {}
    public record SlaProperties(int pickupSlaMinutes) {}
}
