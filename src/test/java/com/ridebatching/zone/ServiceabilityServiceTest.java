package com.ridebatching.zone;

import com.ridebatching.geo.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class ServiceabilityServiceTest {

    private ServiceableZoneRepository repository;
    private ServiceabilityService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ServiceableZoneRepository.class);
        service = new ServiceabilityService(repository);
    }

    private ServiceableZone createZone(Long id, double lat, double lon, double radius) throws Exception {
        ServiceableZone zone = new ServiceableZone("Zone " + id, lat, lon, radius, true);
        Field idField = ServiceableZone.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(zone, id);
        return zone;
    }

    @Test
    void shouldFindContainingZone() throws Exception {
        ServiceableZone zone = createZone(1L, 0.0, 0.0, 5000.0);
        when(repository.findByActiveTrue()).thenReturn(List.of(zone));

        // Roughly 1.1km away from center
        GeoPoint point = new GeoPoint(0.01, 0.0);
        Optional<ServiceableZone> result = service.findZoneFor(point);

        assertTrue(result.isPresent());
        assertEquals(1L, result.get().getId());
    }

    @Test
    void shouldReturnEmptyWhenOutsideAllZones() throws Exception {
        ServiceableZone zone = createZone(1L, 0.0, 0.0, 1000.0);
        when(repository.findByActiveTrue()).thenReturn(List.of(zone));

        // Far away from center
        GeoPoint point = new GeoPoint(1.0, 1.0);
        Optional<ServiceableZone> result = service.findZoneFor(point);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldResolveOverlapToClosestCenter() throws Exception {
        GeoPoint point = new GeoPoint(0.0, 0.0);
        
        // Zone 1: Center at (0.01, 0.0), distance ~1.1km, radius 5km
        ServiceableZone zone1 = createZone(1L, 0.01, 0.0, 5000.0);
        
        // Zone 2: Center at (0.005, 0.0), distance ~0.55km, radius 5km
        ServiceableZone zone2 = createZone(2L, 0.005, 0.0, 5000.0);

        when(repository.findByActiveTrue()).thenReturn(List.of(zone1, zone2));

        Optional<ServiceableZone> result = service.findZoneFor(point);
        
        // Should pick zone 2 because its center is closer
        assertTrue(result.isPresent());
        assertEquals(2L, result.get().getId());
    }

    @Test
    void shouldResolveTieToLowestId() throws Exception {
        GeoPoint point = new GeoPoint(0.0, 0.0);
        
        // Zone 1 and 2 are exactly the same distance from point
        ServiceableZone zone1 = createZone(10L, 0.01, 0.0, 5000.0);
        ServiceableZone zone2 = createZone(5L, 0.01, 0.0, 5000.0);

        when(repository.findByActiveTrue()).thenReturn(List.of(zone1, zone2));

        Optional<ServiceableZone> result = service.findZoneFor(point);
        
        // Should pick zone 5 because it has the lowest ID
        assertTrue(result.isPresent());
        assertEquals(5L, result.get().getId());
    }
}
