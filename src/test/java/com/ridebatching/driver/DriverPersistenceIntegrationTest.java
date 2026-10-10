package com.ridebatching.driver;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.geo.GeoPoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriverPersistenceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DriverRepository driverRepository;

    @Test
    void shouldPersistAndRetrieveDriver() {
        Driver driver = new Driver(UUID.randomUUID(), "Alice", 4);
        driver.updateLocation(new GeoPoint(12.9716, 77.5946));

        driverRepository.saveAndFlush(driver);

        Driver retrieved = driverRepository.findById(driver.getId()).orElseThrow();
        assertThat(retrieved.getName()).isEqualTo("Alice");
        assertThat(retrieved.getStatus()).isEqualTo(DriverStatus.OFFLINE);
        assertThat(retrieved.getVehicleCapacity()).isEqualTo(4);
        assertThat(retrieved.getLocation().latitude()).isEqualTo(12.9716);
    }

    @Test
    void shouldTransitionStateCorrectly() {
        Driver driver = new Driver(UUID.randomUUID(), "Bob", 6);
        driver.updateLocation(new GeoPoint(12.0, 77.0));
        
        // Go online
        driver.goOnline();
        assertThat(driver.getStatus()).isEqualTo(DriverStatus.AVAILABLE);

        // Start break
        driver.startBreak();
        assertThat(driver.getStatus()).isEqualTo(DriverStatus.ON_BREAK);

        // End break
        driver.endBreak();
        assertThat(driver.getStatus()).isEqualTo(DriverStatus.AVAILABLE);

        // Assign trip
        UUID tripId = UUID.randomUUID();
        driver.assignTrip(tripId);
        assertThat(driver.getActiveTripId()).isEqualTo(tripId);

        // Try to go offline while assigned
        assertThatThrownBy(driver::goOffline)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active trip");

        // Complete trip
        driver.completeTrip();
        assertThat(driver.getActiveTripId()).isNull();

        // Go offline
        driver.goOffline();
        assertThat(driver.getStatus()).isEqualTo(DriverStatus.OFFLINE);

        driverRepository.saveAndFlush(driver);
        Driver saved = driverRepository.findById(driver.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(DriverStatus.OFFLINE);
    }
}
