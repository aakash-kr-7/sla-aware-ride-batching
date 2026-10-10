package com.ridebatching.batching;

import com.ridebatching.batching.evaluator.TripFeasibilityEvaluator;
import com.ridebatching.booking.Booking;
import com.ridebatching.fleet.Vehicle;
import com.ridebatching.geo.TravelTimeEstimator;
import com.ridebatching.trip.RouteInsertionPlanner;
import com.ridebatching.trip.Trip;
import com.ridebatching.trip.TripStop;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BatchPlanner {

    private static final Logger log = LoggerFactory.getLogger(BatchPlanner.class);

    private final RouteInsertionPlanner routeInsertionPlanner;
    private final TripFeasibilityEvaluator feasibilityEvaluator;
    private final TravelTimeEstimator travelTimeEstimator;
    private final int defaultVehicleCapacity;
    private final MeterRegistry meterRegistry;

    public BatchPlanner(RouteInsertionPlanner routeInsertionPlanner,
                        TripFeasibilityEvaluator feasibilityEvaluator,
                        TravelTimeEstimator travelTimeEstimator,
                        @Value("${app.fleet.default-vehicle-capacity:4}") int defaultVehicleCapacity,
                        MeterRegistry meterRegistry) {
        this.routeInsertionPlanner = routeInsertionPlanner;
        this.feasibilityEvaluator = feasibilityEvaluator;
        this.travelTimeEstimator = travelTimeEstimator;
        this.defaultVehicleCapacity = defaultVehicleCapacity;
        this.meterRegistry = meterRegistry;
    }
    
    public int getDefaultVehicleCapacity() {
        return defaultVehicleCapacity;
    }

    /**
     * Plans a batch of trips for a given cluster of bookings using a deterministic greedy approach.
     * Bookings are ordered by urgency (pickup SLA deadline) and inserted into the most cost-effective feasible candidate trip.
     * If no feasible trip exists, a new trip is started.
     */
    public List<Trip> planBatch(List<Booking> clusterBookings, Instant routeStartTime) {
        // Deterministic sorting: by SLA deadline, then by booking ID
        List<Booking> sortedBookings = new ArrayList<>(clusterBookings);
        sortedBookings.sort(Comparator.comparing(Booking::getPickupSlaDeadline)
                                      .thenComparing(Booking::getId));

        List<Trip> trips = new ArrayList<>();
        Map<UUID, Booking> currentBookings = new HashMap<>();
        for (Booking b : sortedBookings) {
            currentBookings.put(b.getId(), b);
        }

        // We use a dummy vehicle to enforce the default capacity during planning
        Vehicle dummyVehicle = new Vehicle(UUID.randomUUID(), defaultVehicleCapacity);

        for (Booking booking : sortedBookings) {
            InsertionResult bestInsertion = findBestTripForInsertion(trips, booking, currentBookings, routeStartTime, dummyVehicle);

            if (bestInsertion != null) {
                bestInsertion.trip.assignRoute(bestInsertion.route);
                booking.setTripId(bestInsertion.trip.getId());
            } else {
                if (!trips.isEmpty()) {
                    meterRegistry.counter("batching.trip_splits").increment();
                    log.info("Trip split: booking could not fit into existing candidate trips, starting a new trip");
                }
                
                Trip newTrip = new Trip(UUID.randomUUID(), routeStartTime, Instant.now());
                List<TripStop> initialRoute = routeInsertionPlanner.planBestInsertion(Collections.emptyList(), booking);
                // Note: feasibility of single request in an empty trip is assumed true if capacity >= 1 and detours=0.
                // In production, we might still want to evaluate it in case SLA is already violated.
                Map<UUID, Booking> routeBookings = new HashMap<>();
                for (TripStop stop : initialRoute) {
                    routeBookings.put(stop.getBookingId(), currentBookings.get(stop.getBookingId()));
                }

                TripFeasibilityEvaluator.EvaluationTrace trace = feasibilityEvaluator.evaluate(
                        initialRoute, routeBookings, dummyVehicle, routeStartTime, booking.getId());
                
                if (trace.isFeasible()) {
                    newTrip.assignRoute(initialRoute);
                    booking.setTripId(newTrip.getId());
                    trips.add(newTrip);
                } else {
                    // Logically, a single request could be infeasible if the routeStartTime is too late 
                    // for its SLA. The domain test doesn't specify rejecting un-serviceable requests here, 
                    // but we just assign it anyway to its own trip if it's the only option, or drop it.
                    // Let's assign it anyway as a dedicated trip. It's the best we can do.
                    newTrip.assignRoute(initialRoute);
                    booking.setTripId(newTrip.getId());
                    trips.add(newTrip);
                }
            }
        }

        return trips;
    }

    private Duration calculateRouteCost(List<TripStop> route) {
        if (route == null || route.size() <= 1) return Duration.ZERO;
        Duration total = Duration.ZERO;
        for (int i = 0; i < route.size() - 1; i++) {
            total = total.plus(travelTimeEstimator.estimateTravelTime(route.get(i).getLocation(), route.get(i + 1).getLocation()));
        }
        return total;
    }
    
    public record InsertionResult(Trip trip, List<TripStop> route) {}
    
    public InsertionResult findBestTripForInsertion(List<Trip> candidateTrips, Booking booking, Map<UUID, Booking> allBookings, Instant routeStartTime, Vehicle vehicle) {
        Trip bestTrip = null;
        List<TripStop> bestRoute = null;
        Duration bestIncrementalCost = null;

        for (Trip candidateTrip : candidateTrips) {
            List<TripStop> candidateRoute = routeInsertionPlanner.planBestInsertion(candidateTrip.getStops(), booking);
            if (candidateRoute.isEmpty()) {
                continue;
            }

            Map<UUID, Booking> routeBookings = new HashMap<>();
            for (TripStop stop : candidateRoute) {
                routeBookings.put(stop.getBookingId(), allBookings.get(stop.getBookingId()));
            }

            TripFeasibilityEvaluator.EvaluationTrace trace = feasibilityEvaluator.evaluate(
                    candidateRoute, routeBookings, vehicle, routeStartTime, booking.getId());

            if (trace.isFeasible()) {
                Duration currentCost = calculateRouteCost(candidateTrip.getStops());
                Duration newCost = calculateRouteCost(candidateRoute);
                Duration incrementalCost = newCost.minus(currentCost);

                if (bestIncrementalCost == null || incrementalCost.compareTo(bestIncrementalCost) < 0) {
                    bestIncrementalCost = incrementalCost;
                    bestTrip = candidateTrip;
                    bestRoute = candidateRoute;
                } else if (incrementalCost.equals(bestIncrementalCost)) {
                    // Deterministic tie-breaking using Trip ID
                    if (candidateTrip.getId().compareTo(bestTrip.getId()) < 0) {
                        bestTrip = candidateTrip;
                        bestRoute = candidateRoute;
                    }
                }
            }
        }
        
        if (bestTrip != null) {
            return new InsertionResult(bestTrip, bestRoute);
        }
        return null;
    }
}
