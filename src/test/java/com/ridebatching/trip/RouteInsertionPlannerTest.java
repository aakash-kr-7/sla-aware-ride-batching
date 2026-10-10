package com.ridebatching.trip;

import com.ridebatching.booking.Booking;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.geo.TravelTimeEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouteInsertionPlannerTest {

    private RouteInsertionPlanner planner;

    @BeforeEach
    void setUp() {
        // Use a simple 2D cartesian distance = 1 minute per unit distance for easy mental math
        TravelTimeEstimator cartesianEstimator = (origin, destination) -> {
            double dist = Math.sqrt(Math.pow(origin.latitude() - destination.latitude(), 2) 
                                  + Math.pow(origin.longitude() - destination.longitude(), 2));
            return Duration.ofMinutes((long) dist);
        };
        planner = new RouteInsertionPlanner(cartesianEstimator);
    }

    private Booking mockBooking(UUID id, GeoPoint pickup, GeoPoint dropoff) {
        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(id);
        when(booking.getPickupLocation()).thenReturn(pickup);
        when(booking.getDropoffLocation()).thenReturn(dropoff);
        return booking;
    }

    @Test
    void shouldPlanForEmptyRoute() {
        UUID newBookingId = UUID.randomUUID();
        Booking booking = mockBooking(newBookingId, new GeoPoint(0, 0), new GeoPoint(0, 5));
        
        List<TripStop> result = planner.planBestInsertion(new ArrayList<>(), booking);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getType()).isEqualTo(TripStopType.PICKUP);
        assertThat(result.get(0).getBookingId()).isEqualTo(newBookingId);
        assertThat(result.get(1).getType()).isEqualTo(TripStopType.DROPOFF);
        assertThat(result.get(1).getBookingId()).isEqualTo(newBookingId);
    }

    @Test
    void shouldEvaluateMultipleInsertionsAndChooseLowestCost() {
        // Existing route: A(0,0) -> A(0,10) -> cost is 10
        UUID existingId = UUID.randomUUID();
        TripStop ap = new TripStop(UUID.randomUUID(), existingId, TripStopType.PICKUP, new GeoPoint(0, 0));
        TripStop ad = new TripStop(UUID.randomUUID(), existingId, TripStopType.DROPOFF, new GeoPoint(0, 10));
        List<TripStop> currentRoute = List.of(ap, ad);

        // New booking: B(0,2) -> B(0,8)
        UUID newId = UUID.randomUUID();
        Booking newBooking = mockBooking(newId, new GeoPoint(0, 2), new GeoPoint(0, 8));

        // The most optimal combination should nest B completely inside A's route without backtracking:
        // A(P) -> B(P) -> B(D) -> A(D)
        // Cost: 2 + 6 + 2 = 10 minutes.
        List<TripStop> plannedRoute = planner.planBestInsertion(currentRoute, newBooking);

        assertThat(plannedRoute).hasSize(4);
        assertThat(plannedRoute.get(0).getBookingId()).isEqualTo(existingId);
        assertThat(plannedRoute.get(0).getType()).isEqualTo(TripStopType.PICKUP);
        
        assertThat(plannedRoute.get(1).getBookingId()).isEqualTo(newId);
        assertThat(plannedRoute.get(1).getType()).isEqualTo(TripStopType.PICKUP);
        
        assertThat(plannedRoute.get(2).getBookingId()).isEqualTo(newId);
        assertThat(plannedRoute.get(2).getType()).isEqualTo(TripStopType.DROPOFF);
        
        assertThat(plannedRoute.get(3).getBookingId()).isEqualTo(existingId);
        assertThat(plannedRoute.get(3).getType()).isEqualTo(TripStopType.DROPOFF);
    }

    @Test
    void shouldDeterministicallyBreakTiesByFavouringEarlierInsertions() {
        // Existing route: A(0,0) -> A(0,0) -> cost 0
        UUID existingId = UUID.randomUUID();
        TripStop ap = new TripStop(UUID.randomUUID(), existingId, TripStopType.PICKUP, new GeoPoint(0, 0));
        TripStop ad = new TripStop(UUID.randomUUID(), existingId, TripStopType.DROPOFF, new GeoPoint(0, 0));
        List<TripStop> currentRoute = List.of(ap, ad);

        // New booking: B(0,0) -> B(0,0) -> cost 0
        UUID newId = UUID.randomUUID();
        Booking newBooking = mockBooking(newId, new GeoPoint(0, 0), new GeoPoint(0, 0));

        // All 6 valid insertion combinations will yield a cost of 0.
        // Deterministic tie-breaking should pick the first valid combination evaluated,
        // which places pickup at index 0 and dropoff at index 1.
        List<TripStop> plannedRoute = planner.planBestInsertion(currentRoute, newBooking);

        assertThat(plannedRoute).hasSize(4);
        // B should be placed entirely before A
        assertThat(plannedRoute.get(0).getBookingId()).isEqualTo(newId);
        assertThat(plannedRoute.get(0).getType()).isEqualTo(TripStopType.PICKUP);
        assertThat(plannedRoute.get(1).getBookingId()).isEqualTo(newId);
        assertThat(plannedRoute.get(1).getType()).isEqualTo(TripStopType.DROPOFF);
        
        assertThat(plannedRoute.get(2).getBookingId()).isEqualTo(existingId);
        assertThat(plannedRoute.get(2).getType()).isEqualTo(TripStopType.PICKUP);
        assertThat(plannedRoute.get(3).getBookingId()).isEqualTo(existingId);
        assertThat(plannedRoute.get(3).getType()).isEqualTo(TripStopType.DROPOFF);
    }

    @Test
    void shouldEnsurePickupStrictlyPrecedesDropoff() {
        UUID existingId = UUID.randomUUID();
        TripStop ap = new TripStop(UUID.randomUUID(), existingId, TripStopType.PICKUP, new GeoPoint(0, 0));
        TripStop ad = new TripStop(UUID.randomUUID(), existingId, TripStopType.DROPOFF, new GeoPoint(0, 10));
        List<TripStop> currentRoute = List.of(ap, ad);

        UUID newId = UUID.randomUUID();
        Booking newBooking = mockBooking(newId, new GeoPoint(0, 2), new GeoPoint(0, 8));

        // Let's modify the estimator to throw an exception if drop-off comes before pickup in the proposed route
        // but since we only observe the final result, we can check the invariant by ensuring
        // the returned route has pickup before dropoff. The algorithm intrinsically enforces this,
        // so we're just asserting the output invariant holds.
        List<TripStop> plannedRoute = planner.planBestInsertion(currentRoute, newBooking);
        
        int pickupIndex = -1;
        int dropoffIndex = -1;
        
        for (int i = 0; i < plannedRoute.size(); i++) {
            if (plannedRoute.get(i).getBookingId().equals(newId)) {
                if (plannedRoute.get(i).getType() == TripStopType.PICKUP) {
                    pickupIndex = i;
                } else if (plannedRoute.get(i).getType() == TripStopType.DROPOFF) {
                    dropoffIndex = i;
                }
            }
        }
        
        assertThat(pickupIndex).isNotEqualTo(-1);
        assertThat(dropoffIndex).isNotEqualTo(-1);
        assertThat(pickupIndex).isLessThan(dropoffIndex);
    }
}
