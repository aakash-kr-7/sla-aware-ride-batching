package com.ridebatching.geo;

/**
 * Represents a geographic direction in degrees, measured clockwise from North.
 * Provides circular math for comparing directions safely (e.g. crossing 360 degrees).
 */
public record Bearing(double degrees) {

    public Bearing(double degrees) {
        // Normalize to [0.0, 360.0)
        // Adding 360.0 before modulo handles negative inputs correctly.
        this.degrees = ((degrees % 360.0) + 360.0) % 360.0;
    }

    /**
     * Calculates the shortest angular difference between this bearing and another.
     * 
     * @param other The bearing to compare against
     * @return The absolute angular difference, always between 0.0 and 180.0 degrees.
     */
    public double shortestDifference(Bearing other) {
        double diff = Math.abs(this.degrees - other.degrees);
        return Math.min(diff, 360.0 - diff);
    }

    /**
     * Calculates the initial bearing (forward azimuth) from the start point to the end point.
     * 
     * Uses the standard great-circle initial bearing formula:
     * θ = atan2(sin(Δlong).cos(lat2), cos(lat1).sin(lat2) − sin(lat1).cos(lat2).cos(Δlong))
     * 
     * @param start The starting point
     * @param end The destination point
     * @return The initial bearing from start to end
     */
    public static Bearing between(GeoPoint start, GeoPoint end) {
        double startLatRad = Math.toRadians(start.latitude());
        double endLatRad = Math.toRadians(end.latitude());
        double deltaLonRad = Math.toRadians(end.longitude() - start.longitude());

        double y = Math.sin(deltaLonRad) * Math.cos(endLatRad);
        double x = Math.cos(startLatRad) * Math.sin(endLatRad) -
                   Math.sin(startLatRad) * Math.cos(endLatRad) * Math.cos(deltaLonRad);

        double bearingRad = Math.atan2(y, x);
        double bearingDeg = Math.toDegrees(bearingRad);

        return new Bearing(bearingDeg);
    }
}
