package com.ridebatching.driver;

import com.ridebatching.geo.DistanceCalculator;
import com.ridebatching.geo.GeoPoint;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Ranks eligible drivers by proximity to a given location (usually the first pickup).
 * Note: This currently uses a straight-line distance approximation for deterministic 
 * assessment rather than actual road network ETA.
 */
@Component
public class DriverProximityRanker {

    public DriverProximityRanker() {
    }

    public List<Driver> rankByProximity(List<Driver> eligibleDrivers, GeoPoint origin) {
        return eligibleDrivers.stream()
                .sorted(Comparator.comparingDouble((Driver d) -> 
                                DistanceCalculator.calculateDistanceInMetres(d.getLocation(), origin))
                        .thenComparing(Driver::getId)) // Stable tie-breaking for determinism
                .collect(Collectors.toList());
    }
}
