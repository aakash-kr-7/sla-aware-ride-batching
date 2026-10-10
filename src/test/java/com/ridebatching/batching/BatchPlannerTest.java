package com.ridebatching.batching;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BatchPlannerTest {

    private RouteInsertionPlanner routeInsertionPlanner;
    private TripFeasibilityEvaluator feasibilityEvaluator;
    private TravelTimeEstimator travelTimeEstimator;
    private BatchPlanner planner;

    @BeforeEach
    void setUp() {
        routeInsertionPlanner = mock(RouteInsertionPlanner.class);
        feasibilityEvaluator = mock(TripFeasibilityEvaluator.class);
        travelTimeEstimator = mock(TravelTimeEstimator.class);

        planner = new BatchPlanner(routeInsertionPlanner, feasibilityEvaluator, travelTimeEstimator, 4, new SimpleMeterRegistry());
    }

    private Booking mockBooking(Instant sla) {
        Employee employee = new Employee(UUID.randomUUID(), "Test", "test@test.com", "HR");
        return new Booking(UUID.randomUUID(), employee, new GeoPoint(0, 0), new GeoPoint(1, 1),
                Instant.now(), BookingStatus.PENDING, sla, Instant.now(), UUID.randomUUID().toString());
    }

    @Test
    void shouldReturnEmptyListWhenNoBookings() {
        List<Trip> trips = planner.planBatch(List.of(), Instant.now());
        assertThat(trips).isEmpty();
    }

    @Test
    void shouldCreateOneTripForOneFeasibleBooking() {
        Booking booking = mockBooking(Instant.now().plus(Duration.ofMinutes(15)));
        Instant routeStart = Instant.now();

        List<TripStop> mockRoute = List.of(
                new TripStop(UUID.randomUUID(), booking.getId(), TripStopType.PICKUP, booking.getPickupLocation()),
                new TripStop(UUID.randomUUID(), booking.getId(), TripStopType.DROPOFF, booking.getDropoffLocation())
        );

        when(routeInsertionPlanner.planBestInsertion(any(), eq(booking))).thenReturn(mockRoute);
        when(feasibilityEvaluator.evaluate(any(), any(), any(), eq(routeStart), eq(booking.getId())))
                .thenReturn(new TripFeasibilityEvaluator.EvaluationTrace(UUID.randomUUID(), mockRoute, booking.getId(), List.of(), true, null));
        when(travelTimeEstimator.estimateTravelTime(any(), any())).thenReturn(Duration.ofMinutes(10));

        List<Trip> trips = planner.planBatch(List.of(booking), routeStart);

        assertThat(trips).hasSize(1);
        Trip createdTrip = trips.get(0);
        assertThat(createdTrip.getStops()).hasSize(2);
        assertThat(booking.getTripId()).isEqualTo(createdTrip.getId());
    }

    @Test
    void shouldCreateTwoTripsIfInsertionInfeasible() {
        Booking b1 = mockBooking(Instant.now().plus(Duration.ofMinutes(15)));
        Booking b2 = mockBooking(Instant.now().plus(Duration.ofMinutes(16)));
        Instant routeStart = Instant.now();

        List<TripStop> route1 = List.of(
                new TripStop(UUID.randomUUID(), b1.getId(), TripStopType.PICKUP, b1.getPickupLocation()),
                new TripStop(UUID.randomUUID(), b1.getId(), TripStopType.DROPOFF, b1.getDropoffLocation())
        );

        List<TripStop> route2 = List.of(
                new TripStop(UUID.randomUUID(), b2.getId(), TripStopType.PICKUP, b2.getPickupLocation()),
                new TripStop(UUID.randomUUID(), b2.getId(), TripStopType.DROPOFF, b2.getDropoffLocation())
        );

        when(routeInsertionPlanner.planBestInsertion(List.of(), b1)).thenReturn(route1);
        when(routeInsertionPlanner.planBestInsertion(List.of(), b2)).thenReturn(route2);
        // Try to insert b2 into b1's trip
        List<TripStop> combinedRoute = List.of(); // just some non-empty list
        when(routeInsertionPlanner.planBestInsertion(route1, b2)).thenReturn(combinedRoute);

        // First booking feasible in empty trip
        when(feasibilityEvaluator.evaluate(eq(route1), any(), any(), eq(routeStart), eq(b1.getId())))
                .thenReturn(new TripFeasibilityEvaluator.EvaluationTrace(UUID.randomUUID(), route1, b1.getId(), List.of(), true, null));
        
        // Second booking infeasible in combined trip
        when(feasibilityEvaluator.evaluate(eq(combinedRoute), any(), any(), eq(routeStart), eq(b2.getId())))
                .thenReturn(new TripFeasibilityEvaluator.EvaluationTrace(UUID.randomUUID(), combinedRoute, b2.getId(), List.of(), false, null));

        // Second booking feasible in new trip
        when(feasibilityEvaluator.evaluate(eq(route2), any(), any(), eq(routeStart), eq(b2.getId())))
                .thenReturn(new TripFeasibilityEvaluator.EvaluationTrace(UUID.randomUUID(), route2, b2.getId(), List.of(), true, null));

        List<Trip> trips = planner.planBatch(List.of(b1, b2), routeStart);

        assertThat(trips).hasSize(2);
        assertThat(b1.getTripId()).isEqualTo(trips.get(0).getId());
        assertThat(b2.getTripId()).isEqualTo(trips.get(1).getId());
    }
}
