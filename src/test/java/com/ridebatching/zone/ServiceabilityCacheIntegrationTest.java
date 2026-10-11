package com.ridebatching.zone;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.geo.GeoPoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceabilityCacheIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ServiceabilityService serviceabilityService;

    @Autowired
    private ServiceableZoneRepository zoneRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transaction;

    @Test
    void saveAndFlushMustEvictCachedEmptyList() {
        GeoPoint point = new GeoPoint(10, 10);
        assertThat(serviceabilityService.findZoneFor(point)).isEmpty();
        zoneRepository.saveAndFlush(new ServiceableZone("new", 10, 10, 1000, true));
        assertThat(serviceabilityService.findZoneFor(point)).isPresent();
    }

    @Test
    void deleteByIdMustEvictPreviouslyServiceableZone() {
        GeoPoint point = new GeoPoint(10, 10);
        ServiceableZone zone = zoneRepository.saveAndFlush(new ServiceableZone("removed", 10, 10, 1000, true));
        assertThat(serviceabilityService.findZoneFor(point)).isPresent();
        zoneRepository.deleteById(zone.getId());
        assertThat(serviceabilityService.findZoneFor(point)).isEmpty();
    }

    @Test
    void competingReadBeforeDeleteCommitCannotLeaveStaleServiceability() throws Exception {
        GeoPoint point = new GeoPoint(10, 10);
        ServiceableZone zone = zoneRepository.saveAndFlush(new ServiceableZone("removed", 10, 10, 1000, true));
        assertThat(serviceabilityService.findZoneFor(point)).isPresent();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            transaction.executeWithoutResult(status -> {
                zoneRepository.delete(zone);
                zoneRepository.flush();
                try {
                    assertThat(executor.submit(() -> serviceabilityService.findZoneFor(point))
                            .get(10, java.util.concurrent.TimeUnit.SECONDS)).isPresent();
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            assertThat(serviceabilityService.findZoneFor(point)).isEmpty();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rolledBackZoneReadCannotPublishUncommittedZones() {
        GeoPoint point = new GeoPoint(10, 10);
        transaction.executeWithoutResult(status -> {
            zoneRepository.saveAndFlush(new ServiceableZone("rolled back", 10, 10, 1000, true));
            assertThat(serviceabilityService.findZoneFor(point)).isPresent();
            status.setRollbackOnly();
        });
        assertThat(zoneRepository.count()).isZero();
        assertThat(serviceabilityService.findZoneFor(point)).isEmpty();
    }

    @Test
    void testCacheHitAndEviction() {
        // Given a point
        GeoPoint testPoint = new GeoPoint(10.0, 10.0);
        
        // Ensure no active zones
        Optional<ServiceableZone> result1 = serviceabilityService.findZoneFor(testPoint);
        assertThat(result1).isEmpty();
        
        // Saving through the repository evicts the cached empty zone list.
        ServiceableZone newZone = new ServiceableZone("Zone A", 10.0, 10.0, 5000.0, true);
        zoneRepository.save(newZone); // This evicts cache
        
        // Find zone - this will hit DB and populate cache
        Optional<ServiceableZone> result2 = serviceabilityService.findZoneFor(testPoint);
        assertThat(result2).isPresent();
        assertThat(result2.get().getName()).isEqualTo("Zone A");
        
        // To verify cache hit, let's delete via native query (bypassing Spring Data eviction)
        jdbcTemplate.update("DELETE FROM serviceable_zones WHERE name = 'Zone A'");
        
        // Should STILL be present because of cache hit
        Optional<ServiceableZone> result3 = serviceabilityService.findZoneFor(testPoint);
        assertThat(result3).isPresent();
        assertThat(result3.get().getName()).isEqualTo("Zone A");
        
        // Now use Repository to save a new one, triggering @CacheEvict
        ServiceableZone newerZone = new ServiceableZone("Zone B", 10.0, 10.0, 5000.0, true);
        zoneRepository.save(newerZone);
        
        // Find zone - Cache is evicted, will hit DB and find only Zone B (since A was deleted)
        Optional<ServiceableZone> result4 = serviceabilityService.findZoneFor(testPoint);
        assertThat(result4).isPresent();
        assertThat(result4.get().getName()).isEqualTo("Zone B");
    }
}
