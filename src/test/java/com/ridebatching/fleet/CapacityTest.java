package com.ridebatching.fleet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapacityTest {

    @Test
    void shouldAcceptPositiveCapacity() {
        assertDoesNotThrow(() -> new Capacity(1));
        assertDoesNotThrow(() -> new Capacity(4));
        assertDoesNotThrow(() -> new Capacity(10));
    }

    @Test
    void shouldRejectInvalidVehicleCapacity() {
        IllegalArgumentException exZero = assertThrows(IllegalArgumentException.class, () -> new Capacity(0));
        assertEquals("Vehicle capacity must be strictly positive, was: 0", exZero.getMessage());

        IllegalArgumentException exNegative = assertThrows(IllegalArgumentException.class, () -> new Capacity(-1));
        assertEquals("Vehicle capacity must be strictly positive, was: -1", exNegative.getMessage());
    }
}
