package com.ridebatching.zone;

import com.ridebatching.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceableZoneIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ServiceableZoneRepository repository;

    @Test
    void shouldSaveValidZone() {
        ServiceableZone zone = new ServiceableZone("HQ", 12.9716, 77.5946, 5000.0, true);
        ServiceableZone saved = repository.saveAndFlush(zone);
        assertNotNull(saved.getId());
    }

    @Test
    void shouldRejectInvalidLatitudeConstraint() {
        ServiceableZone zone = new ServiceableZone("Invalid Lat", 91.0, 0.0, 5000.0, true);
        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(zone));
    }

    @Test
    void shouldRejectInvalidLongitudeConstraint() {
        ServiceableZone zone = new ServiceableZone("Invalid Lon", 0.0, 181.0, 5000.0, true);
        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(zone));
    }

    @Test
    void shouldRejectInvalidRadiusConstraint() {
        ServiceableZone zone = new ServiceableZone("Invalid Radius", 0.0, 0.0, 0.0, true);
        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(zone));
    }
}
