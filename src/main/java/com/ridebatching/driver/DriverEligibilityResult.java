package com.ridebatching.driver;

import java.util.Optional;

public record DriverEligibilityResult(boolean isEligible, DriverExclusionReason reason) {
    
    public static DriverEligibilityResult eligible() {
        return new DriverEligibilityResult(true, null);
    }
    
    public static DriverEligibilityResult ineligible(DriverExclusionReason reason) {
        return new DriverEligibilityResult(false, reason);
    }
    
    public Optional<DriverExclusionReason> getReason() {
        return Optional.ofNullable(reason);
    }
}
