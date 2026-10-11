package com.ridebatching.geo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class LocalTravelTimeEstimator implements TravelTimeEstimator {

    private final double circuityFactor;
    private final double averageSpeedKmh;
    private final Duration stopDwellTime;

    public LocalTravelTimeEstimator(
            @Value("${app.geo.circuity-factor:1.35}") double circuityFactor,
            @Value("${app.geo.average-speed-kmh:30.0}") double averageSpeedKmh,
            @Value("${app.geo.stop-dwell-time-seconds:120}") long stopDwellTimeSeconds) {
        if (!Double.isFinite(circuityFactor) || circuityFactor <= 0
                || !Double.isFinite(averageSpeedKmh) || averageSpeedKmh <= 0
                || stopDwellTimeSeconds < 0) {
            throw new IllegalArgumentException("Travel-time factors must be finite and positive; dwell time must be non-negative");
        }
        this.circuityFactor = circuityFactor;
        this.averageSpeedKmh = averageSpeedKmh;
        this.stopDwellTime = Duration.ofSeconds(stopDwellTimeSeconds);
    }

    @Override
    public Duration estimateTravelTime(GeoPoint origin, GeoPoint destination) {
        return estimateApproachTime(origin, destination).plus(stopDwellTime);
    }

    @Override
    public Duration estimateApproachTime(GeoPoint origin, GeoPoint destination) {
        double straightLineDistanceMeters = DistanceCalculator.calculateDistanceInMetres(origin, destination);
        double roadDistanceMeters = straightLineDistanceMeters * circuityFactor;
        
        // Convert speed from km/h to m/s
        double averageSpeedMs = averageSpeedKmh / 3.6;
        
        long travelSeconds = (long) (roadDistanceMeters / averageSpeedMs);
        
        return Duration.ofSeconds(travelSeconds);
    }
}
