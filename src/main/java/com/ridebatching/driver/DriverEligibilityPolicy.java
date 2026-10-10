package com.ridebatching.driver;

import org.springframework.stereotype.Component;

/**
 * Domain policy that determines whether a given driver is eligible 
 * to be dispatched or offered a new trip.
 */
@Component
public class DriverEligibilityPolicy {

    public DriverEligibilityResult evaluate(Driver driver) {
        if (driver.getStatus() == DriverStatus.OFFLINE) {
            return DriverEligibilityResult.ineligible(DriverExclusionReason.OFFLINE);
        }
        
        if (driver.getStatus() == DriverStatus.ON_BREAK) {
            return DriverEligibilityResult.ineligible(DriverExclusionReason.ON_BREAK);
        }
        
        if (driver.getActiveTripId() != null) {
            return DriverEligibilityResult.ineligible(DriverExclusionReason.ALREADY_ASSIGNED_TO_TRIP);
        }
        
        return DriverEligibilityResult.eligible();
    }
}
