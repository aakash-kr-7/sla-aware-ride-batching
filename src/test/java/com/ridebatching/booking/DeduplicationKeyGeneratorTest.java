package com.ridebatching.booking;

import com.ridebatching.geo.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DeduplicationKeyGeneratorTest {

    private DeduplicationKeyGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new DeduplicationKeyGenerator();
    }

    @Test
    void shouldProduceSameKeyForIdenticalLogicalRequests() {
        UUID employeeId = UUID.randomUUID();
        Instant slot = Instant.parse("2026-10-10T12:00:00Z");
        GeoPoint pickup = new GeoPoint(12.9716, 77.5946);
        GeoPoint dropoff = new GeoPoint(12.9352, 77.6245);

        String key1 = generator.generateKey(employeeId, pickup, dropoff, slot);
        String key2 = generator.generateKey(employeeId, pickup, dropoff, slot);

        assertEquals(key1, key2);
    }

    @Test
    void shouldProduceDifferentKeysForDifferentEmployees() {
        UUID employee1 = UUID.randomUUID();
        UUID employee2 = UUID.randomUUID();
        Instant slot = Instant.parse("2026-10-10T12:00:00Z");
        GeoPoint pickup = new GeoPoint(12.9716, 77.5946);
        GeoPoint dropoff = new GeoPoint(12.9352, 77.6245);

        String key1 = generator.generateKey(employee1, pickup, dropoff, slot);
        String key2 = generator.generateKey(employee2, pickup, dropoff, slot);

        assertNotEquals(key1, key2);
    }

    @Test
    void shouldProduceDifferentKeysForDifferentSlots() {
        UUID employeeId = UUID.randomUUID();
        Instant slot1 = Instant.parse("2026-10-10T12:00:00Z");
        Instant slot2 = Instant.parse("2026-10-10T12:15:00Z");
        GeoPoint pickup = new GeoPoint(12.9716, 77.5946);
        GeoPoint dropoff = new GeoPoint(12.9352, 77.6245);

        String key1 = generator.generateKey(employeeId, pickup, dropoff, slot1);
        String key2 = generator.generateKey(employeeId, pickup, dropoff, slot2);

        assertNotEquals(key1, key2);
    }

    @Test
    void shouldProduceDifferentKeysForMeaningfullyDifferentLocations() {
        UUID employeeId = UUID.randomUUID();
        Instant slot = Instant.parse("2026-10-10T12:00:00Z");
        
        GeoPoint pickup1 = new GeoPoint(12.9716, 77.5946);
        // Approximately 110 meters away (0.001 deg difference)
        GeoPoint pickup2 = new GeoPoint(12.9726, 77.5946);
        
        GeoPoint dropoff = new GeoPoint(12.9352, 77.6245);

        String key1 = generator.generateKey(employeeId, pickup1, dropoff, slot);
        String key2 = generator.generateKey(employeeId, pickup2, dropoff, slot);

        assertNotEquals(key1, key2);
    }

    @Test
    void shouldProduceSameKeyForSmallCoordinateNoiseInsideNormalisationBounds() {
        UUID employeeId = UUID.randomUUID();
        Instant slot = Instant.parse("2026-10-10T12:00:00Z");
        
        // Differences in the 5th and 6th decimal places (sub-meter level noise)
        // Both should round to 12.9716 and 77.5946 using HALF_UP rounding
        GeoPoint pickup1 = new GeoPoint(12.971598, 77.594602);
        GeoPoint pickup2 = new GeoPoint(12.971604, 77.594595);
        
        GeoPoint dropoff = new GeoPoint(12.9352, 77.6245);

        String key1 = generator.generateKey(employeeId, pickup1, dropoff, slot);
        String key2 = generator.generateKey(employeeId, pickup2, dropoff, slot);

        assertEquals(key1, key2);
    }
}
