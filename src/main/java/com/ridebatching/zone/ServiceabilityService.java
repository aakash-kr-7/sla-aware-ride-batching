package com.ridebatching.zone;

import com.ridebatching.geo.DistanceCalculator;
import com.ridebatching.geo.GeoPoint;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class ServiceabilityService {

    private final ServiceableZoneRepository zoneRepository;

    public ServiceabilityService(ServiceableZoneRepository zoneRepository) {
        this.zoneRepository = zoneRepository;
    }

    /**
     * Determines which active serviceable zone contains the given pickup point.
     * 
     * If a coordinate falls within multiple overlapping active zones, it is deterministically 
     * resolved to the zone whose center is closest to the coordinate. 
     * If distances are identical, it resolves to the zone with the lowest ID.
     * 
     * @param point The geographic point to check.
     * @return The containing zone, or empty if the point is unserviceable.
     */
    public Optional<ServiceableZone> findZoneFor(GeoPoint point) {
        List<ServiceableZone> activeZones = zoneRepository.findByActiveTrue();
        
        return activeZones.stream()
                .map(zone -> {
                    GeoPoint center = new GeoPoint(zone.getCenterLatitude(), zone.getCenterLongitude());
                    double distance = DistanceCalculator.calculateDistanceInMetres(point, center);
                    return new ZoneMatch(zone, distance);
                })
                .filter(match -> match.distance() <= match.zone().getRadiusMeters())
                .min(Comparator.comparingDouble(ZoneMatch::distance)
                               .thenComparing(match -> match.zone().getId()))
                .map(ZoneMatch::zone);
    }

    private record ZoneMatch(ServiceableZone zone, double distance) {}
}
