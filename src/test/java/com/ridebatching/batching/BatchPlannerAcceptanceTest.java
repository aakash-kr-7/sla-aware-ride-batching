package com.ridebatching.batching;

import com.ridebatching.batching.constraint.CapacityConstraint;
import com.ridebatching.batching.constraint.DetourConstraint;
import com.ridebatching.batching.constraint.PickupSlaConstraint;
import com.ridebatching.batching.evaluator.TripFeasibilityEvaluator;
import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.booking.Employee;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.geo.LocalTravelTimeEstimator;
import com.ridebatching.trip.RouteInsertionPlanner;
import com.ridebatching.trip.Trip;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BatchPlannerAcceptanceTest {

    private BatchPlanner batchPlanner;

    @BeforeEach
    void setUp() {
        // Use realistic but deterministic estimators and constraints
        // circuity = 1.0 (straight line), speed = 36km/h -> 10m/s, dwell time = 0s for simple test math
        LocalTravelTimeEstimator travelTimeEstimator = new LocalTravelTimeEstimator(1.0, 36.0, 0);
        
        CapacityConstraint capacityConstraint = new CapacityConstraint();
        PickupSlaConstraint pickupSlaConstraint = new PickupSlaConstraint(travelTimeEstimator);
        // Generous detour to ensure we hit capacity/SLA limits first
        DetourConstraint detourConstraint = new DetourConstraint(travelTimeEstimator, 1.0, Duration.ofMinutes(30));
        
        TripFeasibilityEvaluator feasibilityEvaluator = new TripFeasibilityEvaluator(
                capacityConstraint, pickupSlaConstraint, detourConstraint);
        
        RouteInsertionPlanner routeInsertionPlanner = new RouteInsertionPlanner(travelTimeEstimator);
        
        batchPlanner = new BatchPlanner(routeInsertionPlanner, feasibilityEvaluator, travelTimeEstimator, 4);
    }

    private Booking createBooking(double pickupLat, double pickupLon, double dropoffLat, double dropoffLon, Instant slaDeadline) {
        Employee employee = new Employee(UUID.randomUUID(), "Employee", "email", "dept");
        return new Booking(
                UUID.randomUUID(),
                employee,
                new GeoPoint(pickupLat, pickupLon),
                new GeoPoint(dropoffLat, dropoffLon),
                Instant.now(),
                BookingStatus.PENDING,
                slaDeadline,
                Instant.now(),
                UUID.randomUUID().toString()
        );
    }

    @Test
    void shouldSplitFiveCompatibleRequestsIntoTwoTripsDueToCapacity() {
        // Given 5 requests starting at the exact same location and going to the exact same destination
        // They are perfectly compatible in terms of direction and time, but capacity is 4.
        Instant routeStart = Instant.parse("2026-10-10T10:00:00Z");
        Instant slaDeadline = routeStart.plus(Duration.ofMinutes(15)); // Plenty of time

        List<Booking> bookings = List.of(
                createBooking(0, 0, 0.01, 0.01, slaDeadline),
                createBooking(0, 0, 0.01, 0.01, slaDeadline),
                createBooking(0, 0, 0.01, 0.01, slaDeadline),
                createBooking(0, 0, 0.01, 0.01, slaDeadline),
                createBooking(0, 0, 0.01, 0.01, slaDeadline)
        );

        // When planner plans batch
        List<Trip> trips = batchPlanner.planBatch(bookings, routeStart);

        // Then it should create one trip with 4 passengers, and another with 1 passenger
        assertThat(trips).hasSize(2);
        
        Trip trip1 = trips.get(0);
        Trip trip2 = trips.get(1);
        
        // 4 pickups, 4 dropoffs = 8 stops
        assertThat(trip1.getStops()).hasSize(8);
        // 1 pickup, 1 dropoff = 2 stops
        assertThat(trip2.getStops()).hasSize(2);
    }

    @Test
    void shouldSplitClusterCompatibleRequestsIfSlaIsMissed() {
        // Given a cluster of 2 requests that are somewhat far apart.
        // Putting them together would make the second request miss its SLA because of the travel time to pick it up.
        
        Instant routeStart = Instant.parse("2026-10-10T10:00:00Z");
        
        // Request 1: Pick up at (0, 0), dropoff at (0, 0.02)
        // SLA is generous
        Booking b1 = createBooking(0, 0, 0, 0.02, routeStart.plus(Duration.ofMinutes(30)));
        
        // Request 2: Pick up at (0.05, 0), dropoff at (0.05, 0.02)
        // Note: 1 degree latitude is ~111km. 0.05 degrees is ~5.5km.
        // At 10m/s, travelling 5.5km takes 550 seconds (9+ minutes).
        // If Request 2's SLA deadline is 5 minutes from routeStart, picking up b1 first will cause b2 to miss SLA.
        Booking b2 = createBooking(0.05, 0, 0.05, 0.02, routeStart.plus(Duration.ofMinutes(5)));
        
        List<Booking> bookings = List.of(b1, b2);

        // When
        List<Trip> trips = batchPlanner.planBatch(bookings, routeStart);

        // Then they are split into two separate trips because grouping them violates b2's SLA
        assertThat(trips).hasSize(2);
    }
}
