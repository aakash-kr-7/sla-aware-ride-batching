package com.ridebatching.trip;

import com.ridebatching.booking.Booking;
import com.ridebatching.geo.TravelTimeEstimator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class RouteInsertionPlanner {

    private final TravelTimeEstimator travelTimeEstimator;

    public RouteInsertionPlanner(TravelTimeEstimator travelTimeEstimator) {
        this.travelTimeEstimator = travelTimeEstimator;
    }

    /**
     * Calculates the best insertion positions for a new booking into an existing route.
     * Evaluates all possible combinations where pickup precedes drop-off.
     * 
     * Time Complexity: O(N^3) where N is the number of stops in the existing route.
     * There are O(N^2) valid insertion pairs (pickup index, dropoff index).
     * For each pair, we construct a new route and calculate its total travel time in O(N).
     * Since N (stops) is typically very small (e.g. <= 8 for a 4-seater cab), O(N^3) is 
     * computationally trivial and preferred for readability. Premature optimization is avoided.
     * 
     * Deterministic tie-breaking: If multiple insertion pairs yield the same added cost,
     * the one with the earliest pickup index (and then earliest drop-off index) wins.
     * 
     * @param currentRoute The existing ordered stops
     * @param booking The new booking to insert
     * @return The new route with lowest added travel time
     */
    public List<TripStop> planBestInsertion(List<TripStop> currentRoute, Booking booking) {
        List<TripStop> bestRoute = null;
        Duration bestCost = null;

        TripStop pickupStop = new TripStop(UUID.randomUUID(), booking.getId(), TripStopType.PICKUP, booking.getPickupLocation());
        TripStop dropoffStop = new TripStop(UUID.randomUUID(), booking.getId(), TripStopType.DROPOFF, booking.getDropoffLocation());

        int n = currentRoute.size();

        for (int pickupIndex = 0; pickupIndex <= n; pickupIndex++) {
            // Drop-off must occur strictly after the pickup
            for (int dropoffIndex = pickupIndex + 1; dropoffIndex <= n + 1; dropoffIndex++) {
                
                List<TripStop> candidateRoute = buildCandidateRoute(currentRoute, pickupIndex, dropoffIndex, pickupStop, dropoffStop);
                Duration cost = calculateRouteCost(candidateRoute);

                if (bestCost == null || cost.compareTo(bestCost) < 0) {
                    bestCost = cost;
                    bestRoute = candidateRoute;
                }
            }
        }

        return bestRoute == null ? new ArrayList<>() : bestRoute;
    }

    private List<TripStop> buildCandidateRoute(List<TripStop> currentRoute, int pickupIndex, int dropoffIndex, TripStop pickup, TripStop dropoff) {
        List<TripStop> route = new ArrayList<>(currentRoute.size() + 2);
        
        for (int i = 0; i <= currentRoute.size() + 1; i++) {
            if (i == pickupIndex) {
                route.add(pickup);
            } else if (i == dropoffIndex) {
                route.add(dropoff);
            } else {
                int originalIndex = i;
                if (i > dropoffIndex) {
                    originalIndex -= 2;
                } else if (i > pickupIndex) {
                    originalIndex -= 1;
                }
                route.add(currentRoute.get(originalIndex));
            }
        }
        return route;
    }

    private Duration calculateRouteCost(List<TripStop> route) {
        if (route.size() <= 1) {
            return Duration.ZERO;
        }

        Duration total = Duration.ZERO;
        for (int i = 0; i < route.size() - 1; i++) {
            total = total.plus(travelTimeEstimator.estimateTravelTime(route.get(i).getLocation(), route.get(i + 1).getLocation()));
        }
        return total;
    }
}
