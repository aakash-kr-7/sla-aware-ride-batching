package com.ridebatching.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational parameters externalized per engineering assumptions (D01, D02, D04).
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    BatchingProperties batching,
    SlaProperties sla,
    GeoProperties geo
) {
    public record BatchingProperties(int windowDurationMinutes, int maxBearingAngleDiffDegrees) {}
    public record SlaProperties(int pickupSlaMinutes) {}
    public record GeoProperties(int dedupCoordinatePrecision) {}
}
