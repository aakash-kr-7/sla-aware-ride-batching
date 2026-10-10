package com.ridebatching.zone;

import com.ridebatching.config.CacheConfig;
import com.ridebatching.geo.GeoPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ServiceabilityCacheIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ServiceabilityService serviceabilityService;

    @Autowired
    private ServiceableZoneRepository zoneRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setup() {
        zoneRepository.deleteAll();
        // Clear cache
        Cache cache = cacheManager.getCache(CacheConfig.ACTIVE_ZONES_CACHE);
        if (cache != null) {
            cache.clear();
        }
    }
    
    @AfterEach
    void tearDown() {
        zoneRepository.deleteAll();
    }

    @Test
    void testCacheHitAndEviction() {
        // Given a point
        GeoPoint testPoint = new GeoPoint(10.0, 10.0);
        
        // Ensure no active zones
        Optional<ServiceableZone> result1 = serviceabilityService.findZoneFor(testPoint);
        assertThat(result1).isEmpty();
        
        // Create an active zone but bypass repository save so we can test the cache hit
        // Wait, if we use SQL to insert it won't evict the cache.
        // Let's just create through repository, which evicts the cache.
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
