package com.ridebatching.geo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DistanceCalculatorTest {

    // Using a delta for double comparison because floating point math introduces small rounding errors.
    private static final double DELTA_METRES = 1.0;

    @Test
    void shouldReturnZeroForSamePoint() {
        GeoPoint point = new GeoPoint(51.5074, -0.1278);
        double distance = DistanceCalculator.calculateDistanceInMetres(point, point);
        assertEquals(0.0, distance, DELTA_METRES);
    }

    @Test
    void shouldCalculateApproximateDistanceBetweenTwoPoints() {
        GeoPoint equator1 = new GeoPoint(0.0, 0.0);
        GeoPoint equator2 = new GeoPoint(1.0, 0.0); // 1 degree north
        
        // 1 degree along a great circle with R=6371km is (pi * 6371000) / 180 metres
        double expectedMetres = (Math.PI * 6371000.0) / 180.0;
        
        double distance = DistanceCalculator.calculateDistanceInMetres(equator1, equator2);
        assertEquals(expectedMetres, distance, DELTA_METRES);
    }

    @Test
    void shouldBeSafeAcrossAntimeridian() {
        // Two points on the equator, one at 179.0, one at -179.0
        // They are 2 degrees apart in longitude, wrapping across the antimeridian (180 / -180).
        GeoPoint point1 = new GeoPoint(0.0, 179.0);
        GeoPoint point2 = new GeoPoint(0.0, -179.0);
        
        // Distance should be 2 degrees along the equator
        double expectedMetres = 2.0 * ((Math.PI * 6371000.0) / 180.0);
        
        double distance = DistanceCalculator.calculateDistanceInMetres(point1, point2);
        assertEquals(expectedMetres, distance, DELTA_METRES);
    }
}
