package com.ridebatching.geo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BearingTest {

    private static final double DELTA = 0.000001;

    @Test
    void shouldNormalizeDegrees() {
        assertEquals(0.0, new Bearing(0).degrees(), DELTA);
        assertEquals(0.0, new Bearing(360).degrees(), DELTA);
        assertEquals(90.0, new Bearing(450).degrees(), DELTA);
        assertEquals(270.0, new Bearing(-90).degrees(), DELTA);
        assertEquals(180.0, new Bearing(-540).degrees(), DELTA);
    }

    @Test
    void shouldCalculateShortestDifference() {
        Bearing north = new Bearing(0);
        Bearing northEast = new Bearing(45);
        Bearing southEast = new Bearing(135);
        Bearing south = new Bearing(180);
        
        assertEquals(45.0, north.shortestDifference(northEast), DELTA);
        assertEquals(135.0, north.shortestDifference(southEast), DELTA);
        assertEquals(180.0, north.shortestDifference(south), DELTA);
        
        // Circular wrap-around tests
        Bearing justWestOfNorth = new Bearing(359);
        Bearing justEastOfNorth = new Bearing(1);
        assertEquals(2.0, justWestOfNorth.shortestDifference(justEastOfNorth), DELTA);
        assertEquals(2.0, justEastOfNorth.shortestDifference(justWestOfNorth), DELTA);
        
        Bearing bearing300 = new Bearing(300);
        Bearing bearing30 = new Bearing(30);
        // Distance from 300 to 360 is 60, plus 30 is 90
        assertEquals(90.0, bearing300.shortestDifference(bearing30), DELTA);
    }

    @Test
    void shouldCalculateInitialBearingBetweenPoints() {
        GeoPoint origin = new GeoPoint(0, 0);
        
        // North
        GeoPoint northPoint = new GeoPoint(1, 0);
        assertEquals(0.0, Bearing.between(origin, northPoint).degrees(), DELTA);
        
        // East
        GeoPoint eastPoint = new GeoPoint(0, 1);
        assertEquals(90.0, Bearing.between(origin, eastPoint).degrees(), DELTA);
        
        // South
        GeoPoint southPoint = new GeoPoint(-1, 0);
        assertEquals(180.0, Bearing.between(origin, southPoint).degrees(), DELTA);
        
        // West
        GeoPoint westPoint = new GeoPoint(0, -1);
        assertEquals(270.0, Bearing.between(origin, westPoint).degrees(), DELTA);
    }
}
