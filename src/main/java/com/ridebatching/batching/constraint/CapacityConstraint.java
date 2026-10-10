package com.ridebatching.batching.constraint;

import com.ridebatching.batching.FeasibilityResult;
import com.ridebatching.fleet.Vehicle;

public class CapacityConstraint {

    /**
     * Evaluates if a given vehicle has enough capacity for a requested number of concurrent passengers.
     * 
     * @param vehicle The assigned vehicle
     * @param peakConcurrentPassengers The peak number of passengers onboard at any point during a trip
     * @return A FeasibilityResult indicating whether the constraint holds
     */
    public FeasibilityResult evaluate(Vehicle vehicle, int peakConcurrentPassengers) {
        int seats = vehicle.getCapacity().seats();
        if (peakConcurrentPassengers <= seats) {
            return FeasibilityResult.feasible();
        } else {
            return FeasibilityResult.infeasible("Peak concurrent passengers (" + peakConcurrentPassengers + 
                ") exceeds vehicle capacity (" + seats + ")");
        }
    }
}
