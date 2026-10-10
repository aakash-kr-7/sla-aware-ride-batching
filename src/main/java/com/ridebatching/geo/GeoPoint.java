package com.ridebatching.geo;

/**
 * Represents a geographic coordinate on Earth.
 */
public record GeoPoint(double latitude, double longitude) {
    public GeoPoint {
        if (latitude < -90.0 || latitude > 90.0) {
            throw new InvalidCoordinateException("Latitude must be between -90 and 90 degrees");
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new InvalidCoordinateException("Longitude must be between -180 and 180 degrees");
        }
    }
}
