package com.ridebatching.driver;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DriverEligibilityPolicyTest {

    private final DriverEligibilityPolicy policy = new DriverEligibilityPolicy();

    @Test
    void offlineDriverIsNotEligible() {
        Driver driver = new Driver(UUID.randomUUID(), "Alice", 4);
        // By default status is OFFLINE
        
        DriverEligibilityResult result = policy.evaluate(driver);
        
        assertThat(result.isEligible()).isFalse();
        assertThat(result.reason()).isEqualTo(DriverExclusionReason.OFFLINE);
    }

    @Test
    void driverOnBreakIsNotEligible() {
        Driver driver = new Driver(UUID.randomUUID(), "Bob", 4);
        driver.goOnline();
        driver.startBreak();
        
        DriverEligibilityResult result = policy.evaluate(driver);
        
        assertThat(result.isEligible()).isFalse();
        assertThat(result.reason()).isEqualTo(DriverExclusionReason.ON_BREAK);
    }

    @Test
    void busyDriverIsNotEligible() {
        Driver driver = new Driver(UUID.randomUUID(), "Charlie", 4);
        driver.goOnline();
        driver.assignTrip(UUID.randomUUID());
        
        DriverEligibilityResult result = policy.evaluate(driver);
        
        assertThat(result.isEligible()).isFalse();
        assertThat(result.reason()).isEqualTo(DriverExclusionReason.ALREADY_ASSIGNED_TO_TRIP);
    }

    @Test
    void availableUnassignedDriverIsEligible() {
        Driver driver = new Driver(UUID.randomUUID(), "Diana", 4);
        driver.goOnline();
        
        DriverEligibilityResult result = policy.evaluate(driver);
        
        assertThat(result.isEligible()).isTrue();
        assertThat(result.getReason()).isEmpty();
    }
}
