package com.ridebatching.batching;

/**
 * Explicit outcome of evaluating a candidate trip against hard business constraints.
 * Returning this rather than throwing exceptions prevents costly stack trace generation 
 * during heavy combinatorial search over thousands of candidate trips.
 */
public record FeasibilityResult(boolean isFeasible, String reason) {
    
    public static FeasibilityResult feasible() {
        return new FeasibilityResult(true, null);
    }
    
    public static FeasibilityResult infeasible(String reason) {
        return new FeasibilityResult(false, reason);
    }
}
