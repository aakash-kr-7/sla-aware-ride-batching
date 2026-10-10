package com.ridebatching.batching.constraint;

import com.ridebatching.batching.FeasibilityResult;
import com.ridebatching.fleet.Vehicle;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapacityConstraintTest {

    private final CapacityConstraint constraint = new CapacityConstraint();

    @Test
    void shouldBeFeasibleWhenWithinCapacity() {
        Vehicle vehicle = new Vehicle(UUID.randomUUID(), 4);
        
        FeasibilityResult result = constraint.evaluate(vehicle, 2);
        
        assertTrue(result.isFeasible());
        assertNull(result.reason());
    }

    @Test
    void shouldBeFeasibleWhenExactlyFull() {
        Vehicle vehicle = new Vehicle(UUID.randomUUID(), 4);
        
        FeasibilityResult result = constraint.evaluate(vehicle, 4);
        
        assertTrue(result.isFeasible());
        assertNull(result.reason());
    }

    @Test
    void shouldBeInfeasibleWhenOneSeatOverCapacity() {
        Vehicle vehicle = new Vehicle(UUID.randomUUID(), 4);
        
        FeasibilityResult result = constraint.evaluate(vehicle, 5);
        
        assertFalse(result.isFeasible());
        assertEquals("Peak concurrent passengers (5) exceeds vehicle capacity (4)", result.reason());
    }
}
