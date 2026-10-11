package com.ridebatching.batching;

import com.ridebatching.batching.constraint.CapacityConstraint;
import com.ridebatching.batching.constraint.DetourConstraint;
import com.ridebatching.batching.constraint.PickupSlaConstraint;
import com.ridebatching.batching.evaluator.TripFeasibilityEvaluator;
import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.booking.Employee;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.geo.TravelTimeEstimator;
import com.ridebatching.trip.RouteInsertionPlanner;
import com.ridebatching.trip.Trip;
import com.ridebatching.trip.TripStop;
import com.ridebatching.trip.TripStopType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Random;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generated-input checks for route order, uniqueness, capacity and pickup SLA.
 */
class RoutePlannerInvariantTest {

    private BatchPlanner batchPlanner;
    private TravelTimeEstimator estimator;
    private final int VEHICLE_CAPACITY = 4;
    private final double MAX_RELATIVE_DETOUR = 0.40;
    private final Duration MAX_ABSOLUTE_DETOUR = Duration.ofMinutes(15);
    
    @BeforeEach
    void setUp() {
        // A simple Euclidean distance-based travel time estimator (1 minute per degree for testing)
        estimator = (origin, destination) -> {
            double dist = Math.sqrt(Math.pow(origin.latitude() - destination.latitude(), 2) + 
                                    Math.pow(origin.longitude() - destination.longitude(), 2));
            return Duration.ofMinutes((long) (dist * 60));
        };
        
        TripFeasibilityEvaluator evaluator = new TripFeasibilityEvaluator(
            new CapacityConstraint(),
            new PickupSlaConstraint(estimator),
            new DetourConstraint(estimator, MAX_RELATIVE_DETOUR, MAX_ABSOLUTE_DETOUR),
            new SimpleMeterRegistry()
        );
        
        RouteInsertionPlanner insertionPlanner = new RouteInsertionPlanner(estimator);
        
        batchPlanner = new BatchPlanner(
            insertionPlanner, 
            evaluator, 
            estimator, 
            VEHICLE_CAPACITY, 
            new SimpleMeterRegistry()
        );
    }
    
    @RepeatedTest(100)
    @DisplayName("Route planner invariants hold for random bookings")
    void testRoutePlannerInvariants(org.junit.jupiter.api.RepetitionInfo repetition) {
        long seed = 20261011L + repetition.getCurrentRepetition();
        Instant routeStartTime = Instant.parse("2026-10-11T00:00:00Z");
        List<Booking> bookings = generateRandomBookings(10, routeStartTime, new Random(seed));
        
        List<Trip> trips = batchPlanner.planBatch(bookings, routeStartTime);
        
        Map<UUID, Booking> bookingMap = bookings.stream().collect(Collectors.toMap(Booking::getId, b -> b));
        
        Set<UUID> allPlannedBookings = new HashSet<>();
        
        for (Trip trip : trips) {
            List<TripStop> route = trip.getStops();
            
            // Invariant 2: No accepted trip exceeds capacity
            int currentLoad = 0;
            for (TripStop stop : route) {
                if (stop.getType() == TripStopType.PICKUP) {
                    currentLoad++;
                    assertThat(currentLoad).as("Capacity exceeded").isLessThanOrEqualTo(VEHICLE_CAPACITY);
                } else {
                    currentLoad--;
                }
            }
            
            // Invariant 1: Pickup always occurs before its corresponding drop
            // Invariant 5: Each booking appears at most once (one pickup, one drop)
            Set<UUID> pickedUp = new HashSet<>();
            Set<UUID> droppedOff = new HashSet<>();
            
            for (TripStop stop : route) {
                UUID bId = stop.getBookingId();
                if (stop.getType() == TripStopType.PICKUP) {
                    assertTrue(pickedUp.add(bId), "Duplicate pickup for booking " + bId);
                } else if (stop.getType() == TripStopType.DROPOFF) {
                    assertTrue(pickedUp.contains(bId), "Dropoff before pickup for booking " + bId);
                    assertTrue(droppedOff.add(bId), "Duplicate dropoff for booking " + bId);
                }
            }
            assertThat(droppedOff).as("All pickups have one dropoff, seed=%s", seed).isEqualTo(pickedUp);
            assertThat(currentLoad).isZero();
            
            // Collect to verify global "at most once" later
            for (UUID bId : pickedUp) {
                assertTrue(allPlannedBookings.add(bId), "Booking " + bId + " is planned in multiple trips");
            }
            
            // Check pickup SLA
            Duration tripTime = Duration.ZERO;
            for (int i = 0; i < route.size(); i++) {
                TripStop current = route.get(i);
                Booking b = bookingMap.get(current.getBookingId());
                
                // Track time
                if (i > 0) {
                    tripTime = tripTime.plus(estimator.estimateTravelTime(route.get(i-1).getLocation(), current.getLocation()));
                }
                
                if (current.getType() == TripStopType.PICKUP) {
                    Instant pickupTime = routeStartTime.plus(tripTime);
                    // Invariant 3: every accepted trip satisfies SLA
                    assertThat(pickupTime).as("SLA violated for " + b.getId())
                            .isBeforeOrEqualTo(b.getPickupSlaDeadline());
                }
                
            }

            // Independently reconstruct every rider's in-vehicle duration instead of trusting the evaluator's verdict.
            Map<UUID, Duration> pickupTimes = new java.util.HashMap<>();
            Duration elapsed = Duration.ZERO;
            for (int i = 0; i < route.size(); i++) {
                TripStop stop = route.get(i);
                if (i > 0) elapsed = elapsed.plus(estimator.estimateTravelTime(route.get(i - 1).getLocation(), stop.getLocation()));
                if (stop.getType() == TripStopType.PICKUP) {
                    pickupTimes.put(stop.getBookingId(), elapsed);
                } else {
                    Booking rider = bookingMap.get(stop.getBookingId());
                    Duration direct = estimator.estimateTravelTime(rider.getPickupLocation(), rider.getDropoffLocation());
                    Duration extra = elapsed.minus(pickupTimes.get(rider.getId())).minus(direct);
                    assertThat(extra).as("Absolute detour, seed=%s, rider=%s", seed, rider.getId()).isLessThanOrEqualTo(MAX_ABSOLUTE_DETOUR);
                    assertThat(extra.toMillis()).as("Relative detour, seed=%s, rider=%s", seed, rider.getId())
                            .isLessThanOrEqualTo((long) (direct.toMillis() * MAX_RELATIVE_DETOUR));
                }
            }
        }
    }
    
    private List<Booking> generateRandomBookings(int count, Instant baseTime, Random rand) {
        List<Booking> list = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            Employee emp = new Employee(new UUID(rand.nextLong(), rand.nextLong()), "EMP" + i, "Name", "email@test.com");
            // Random coordinates within a small bounding box
            GeoPoint pickup = new GeoPoint(rand.nextDouble(12.0, 13.0), rand.nextDouble(77.0, 78.0));
            GeoPoint dropoff = new GeoPoint(rand.nextDouble(12.0, 13.0), rand.nextDouble(77.0, 78.0));
            
            // Random SLA deadline (10 to 60 mins from baseTime)
            Instant sla = baseTime.plus(Duration.ofMinutes(rand.nextInt(10, 60)));
            
            Booking b = new Booking(
                new UUID(rand.nextLong(), rand.nextLong()),
                emp,
                pickup,
                dropoff,
                baseTime,
                BookingStatus.PENDING,
                sla,
                baseTime,
                "token" + i
            );
            list.add(b);
        }
        return list;
    }
}
