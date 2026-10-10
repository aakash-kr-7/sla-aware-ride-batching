package com.ridebatching.batching;

import java.time.Instant;
import java.util.UUID;

/**
 * Explicit outcome of evaluating a candidate trip against hard business constraints.
 * Returning this rather than throwing exceptions prevents costly stack trace generation 
 * during heavy combinatorial search over thousands of candidate trips.
 */
public record FeasibilityResult(boolean isFeasible, String reason, RejectionDetails rejectionDetails) {
    
    public record RejectionDetails(String constraintCode, UUID affectedBooking, Instant estimatedPickup, Instant deadline) {}
    
    public static FeasibilityResult feasible() {
        return new FeasibilityResult(true, null, null);
    }
    
    public static FeasibilityResult infeasible(String reason) {
        return new FeasibilityResult(false, reason, null);
    }

    public static FeasibilityResult infeasible(String reason, RejectionDetails details) {
        return new FeasibilityResult(false, reason, details);
    }
}
