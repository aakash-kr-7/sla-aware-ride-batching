package com.ridebatching.geo;

public final class DistanceCalculator {
    // Standard Earth radius used for approximating distances.
    // The Earth is not a perfect sphere, but this provides acceptable accuracy for city-scale distances.
    private static final double EARTH_RADIUS_METRES = 6371000.0;

    private DistanceCalculator() {
        // Prevent instantiation of utility class
    }

    /**
     * Calculates the great-circle distance between two geographic points.
     * 
     * Uses the Haversine formula, which remains well-conditioned for small distances
     * and inherently handles wrap-around at the antimeridian without explicit conditional logic.
     * 
     * @param point1 The starting location
     * @param point2 The destination location
     * @return The approximate distance in metres
     */
    public static double calculateDistanceInMetres(GeoPoint point1, GeoPoint point2) {
        double lat1Rad = Math.toRadians(point1.latitude());
        double lat2Rad = Math.toRadians(point2.latitude());
        double deltaLatRad = Math.toRadians(point2.latitude() - point1.latitude());
        double deltaLonRad = Math.toRadians(point2.longitude() - point1.longitude());

        double a = Math.sin(deltaLatRad / 2) * Math.sin(deltaLatRad / 2)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad)
                * Math.sin(deltaLonRad / 2) * Math.sin(deltaLonRad / 2);

        // a should theoretically not exceed 1.0, but floating-point errors can push it slightly over.
        // Math.min protects against NaN from Math.sqrt if 'a' becomes 1.0000000000000002
        a = Math.min(1.0, a);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_METRES * c;
    }
}
