package com.ridebatching.geo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GeoPointTest {

    @Test
    void shouldRejectNonFiniteCoordinates() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(InvalidCoordinateException.class, () -> new GeoPoint(value, 0));
            assertThrows(InvalidCoordinateException.class, () -> new GeoPoint(0, value));
        }
    }

    @Test
    void shouldAcceptValidCoordinates() {
        assertDoesNotThrow(() -> new GeoPoint(45.0, 90.0));
        assertDoesNotThrow(() -> new GeoPoint(-90.0, -180.0));
        assertDoesNotThrow(() -> new GeoPoint(90.0, 180.0));
        assertDoesNotThrow(() -> new GeoPoint(0.0, 0.0));
    }

    @Test
    void shouldRejectInvalidLatitude() {
        InvalidCoordinateException exception = assertThrows(
            InvalidCoordinateException.class, 
            () -> new GeoPoint(90.1, 0.0)
        );
        assertEquals("Latitude must be between -90 and 90 degrees", exception.getMessage());

        assertThrows(InvalidCoordinateException.class, () -> new GeoPoint(-90.1, 0.0));
    }

    @Test
    void shouldRejectInvalidLongitude() {
        InvalidCoordinateException exception = assertThrows(
            InvalidCoordinateException.class, 
            () -> new GeoPoint(0.0, 180.1)
        );
        assertEquals("Longitude must be between -180 and 180 degrees", exception.getMessage());

        assertThrows(InvalidCoordinateException.class, () -> new GeoPoint(0.0, -180.1));
    }
}
