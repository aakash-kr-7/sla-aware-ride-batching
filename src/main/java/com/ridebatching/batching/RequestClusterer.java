package com.ridebatching.batching;

import com.ridebatching.booking.Booking;
import com.ridebatching.config.AppProperties;
import com.ridebatching.geo.Bearing;
import com.ridebatching.zone.ServiceabilityService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Groups compatible requests into candidate trips based on their pickup zone and heading.
 *
 * Algorithm (Greedy Leader-Based Clustering):
 * 1. Groups all pending bookings by their current serviceable pickup zone ID.
 * 2. Sorts bookings deterministically (createdAt, then UUID) within each zone.
 * 3. Iterates over the sorted bookings:
 *    a. For each booking, compute its overall trip bearing (pickup -> dropoff).
 *    b. Compare it against the "leader" (first booking) of each existing cluster.
 *    c. If the bearing difference is <= configured threshold (e.g., 45 degrees), append it to the cluster.
 *    d. Otherwise, form a new cluster with the booking as the leader.
 * 
 * Complexity:
 * - Zone Resolution: O(N * Z) where N is bookings and Z is active zones.
 * - Sorting: O(N log N).
 * - Clustering: O(N * C) where C is the number of clusters. Worst-case O(N^2) if all bookings diverge.
 */
@Component
public class RequestClusterer {
    private static final Logger log = LoggerFactory.getLogger(RequestClusterer.class);

    private final ServiceabilityService serviceabilityService;
    private final double maxBearingAngleDiffDegrees;

    public RequestClusterer(ServiceabilityService serviceabilityService,
                            AppProperties appProperties) {
        this.serviceabilityService = serviceabilityService;
        this.maxBearingAngleDiffDegrees = appProperties.batching().maxBearingAngleDiffDegrees();
        if (maxBearingAngleDiffDegrees < 0 || maxBearingAngleDiffDegrees > 180) {
            throw new IllegalArgumentException("Direction threshold must be between 0 and 180 degrees");
        }
    }

    public List<List<Booking>> clusterBookings(List<Booking> bookings) {
        Map<Long, List<Booking>> byZone = new TreeMap<>();
        for (Booking booking : bookings) {
            var zone = serviceabilityService.findZoneFor(booking.getPickupLocation());
            if (zone.isPresent()) {
                byZone.computeIfAbsent(zone.get().getId(), ignored -> new ArrayList<>()).add(booking);
            } else {
                // A zone may have been deactivated after admission. Never batch a sentinel zone.
                log.warn("Booking left pending: pickup has no active service zone, bookingId={}", booking.getId());
            }
        }

        List<List<Booking>> allClusters = new ArrayList<>();
        
        byZone.keySet().stream().sorted().forEach(zoneId -> {
            List<Booking> zoneBookings = byZone.get(zoneId);
            
            zoneBookings.sort(Comparator.comparing(Booking::getCreatedAt)
                                        .thenComparing(Booking::getId));
            
            allClusters.addAll(clusterWithinZone(zoneBookings));
        });
        
        return allClusters;
    }

    private List<List<Booking>> clusterWithinZone(List<Booking> zoneBookings) {
        List<List<Booking>> clusters = new ArrayList<>();
        
        for (Booking booking : zoneBookings) {
            Bearing bookingBearing = Bearing.between(booking.getPickupLocation(), booking.getDropoffLocation());
            boolean added = false;
            
            for (List<Booking> cluster : clusters) {
                Booking leader = cluster.get(0);
                Bearing leaderBearing = Bearing.between(leader.getPickupLocation(), leader.getDropoffLocation());
                
                if (leaderBearing.shortestDifference(bookingBearing) <= maxBearingAngleDiffDegrees) {
                    cluster.add(booking);
                    added = true;
                    break;
                }
            }
            
            if (!added) {
                List<Booking> newCluster = new ArrayList<>();
                newCluster.add(booking);
                clusters.add(newCluster);
            }
        }
        
        return clusters;
    }
}
