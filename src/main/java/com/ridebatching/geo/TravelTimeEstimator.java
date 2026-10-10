package com.ridebatching.geo;

import java.time.Duration;

public interface TravelTimeEstimator {
    /**
     * Estimates the travel duration between two geographic points.
     * 
     * @param origin The starting location.
     * @param destination The destination location.
     * @return The estimated duration required to travel between the points, 
     *         inclusive of any required destination dwell/service time.
     */
    Duration estimateTravelTime(GeoPoint origin, GeoPoint destination);
}
