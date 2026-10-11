package com.ridebatching.batching;

import com.ridebatching.batching.constraint.*;
import com.ridebatching.batching.evaluator.TripFeasibilityEvaluator;
import com.ridebatching.booking.*;
import com.ridebatching.fleet.Vehicle;
import com.ridebatching.geo.*;
import com.ridebatching.trip.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FeasibleInsertionRegressionTest {
    private static final Instant START = Instant.parse("2026-10-11T10:00:00Z");
    private final TravelTimeEstimator eta = new LocalTravelTimeEstimator(1, 36, 0);
    private final RouteInsertionPlanner insertion = new RouteInsertionPlanner(eta);
    private final TripFeasibilityEvaluator evaluator = new TripFeasibilityEvaluator(new CapacityConstraint(),
            new PickupSlaConstraint(eta), new DetourConstraint(eta, 3, Duration.ofMinutes(30)), new SimpleMeterRegistry());
    private final BatchPlanner planner = new BatchPlanner(insertion, evaluator, eta, 4, new SimpleMeterRegistry());

    @Test void moreExpensiveInsertionSatisfiesBothDeadlinesWhenTheCheapestMissesOne() {
        Booking a = booking(1, 0, .02, 120), b = booking(2, .01, .019, 50);
        Trip existing = new Trip(new UUID(0, 3), START, START);
        existing.assignRoute(insertion.planBestInsertion(List.of(), a));
        Map<UUID, Booking> riders = Map.of(a.getId(), a, b.getId(), b);
        Vehicle vehicle = new Vehicle(new UUID(0, 4), 4);
        List<TripStop> cheapest = insertion.planBestInsertion(existing.getStops(), b);
        assertThat(evaluator.evaluate(cheapest, riders, vehicle, START, b.getId()).isRejectedBy("PICKUP_SLA")).isTrue();

        var selected = planner.findBestTripForInsertion(List.of(existing), b, riders, START, vehicle);
        assertThat(selected).isNotNull();
        assertThat(selected.trip()).isSameAs(existing);
        assertThat(cost(selected.route())).isGreaterThan(cost(cheapest));
        assertThat(selected.route()).extracting(TripStop::getBookingId).containsExactly(b.getId(), a.getId(), b.getId(), a.getId());
        assertThat(evaluator.evaluate(selected.route(), riders, vehicle, START, b.getId()).isFeasible()).isTrue();
        // Independent deadline/cost reconstruction; all six insertion pairs are known here.
        assertThat(eta.estimateTravelTime(b.getPickupLocation(), a.getPickupLocation()).getSeconds()).isLessThanOrEqualTo(120);
        assertThat(cost(selected.route())).isEqualTo(Duration.ofSeconds(333));
        assertThat(planner.planBatch(List.of(a, b), START)).hasSize(1);
    }

    @Test void noFeasibleAlternativeLeavesTheExistingRouteUntouched() {
        Booking a = booking(1, 0, .02, 0), b = booking(2, .01, .019, 0);
        Trip trip = new Trip(new UUID(0, 3), START, START);
        trip.assignRoute(insertion.planBestInsertion(List.of(), a));
        List<TripStop> before = List.copyOf(trip.getStops());
        assertThat(planner.findBestTripForInsertion(List.of(trip), b, Map.of(a.getId(), a, b.getId(), b),
                START, new Vehicle(new UUID(0, 4), 4))).isNull();
        assertThat(trip.getStops()).containsExactlyElementsOf(before);
        assertThat(b.getTripId()).isNull();
    }

    private Booking booking(long id, double pickup, double drop, long seconds) {
        return new Booking(new UUID(0, id), null, new GeoPoint(0, pickup), new GeoPoint(0, drop),
                START, BookingStatus.PENDING, START.plusSeconds(seconds), START, "alternative-" + id);
    }
    private Duration cost(List<TripStop> route) {
        Duration total = Duration.ZERO;
        for (int i = 1; i < route.size(); i++) total = total.plus(eta.estimateTravelTime(route.get(i-1).getLocation(), route.get(i).getLocation()));
        return total;
    }
}
