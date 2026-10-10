package com.ridebatching.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Window, clustering and pickup SLA settings consumed by the booking domain.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    BatchingProperties batching,
    SlaProperties sla
) {
    public record BatchingProperties(int windowDurationMinutes, int maxBearingAngleDiffDegrees) {}
    public record SlaProperties(int pickupSlaMinutes) {}
}
