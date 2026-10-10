package com.ridebatching.batching.evaluator;

import com.ridebatching.batching.FeasibilityResult;
import com.ridebatching.batching.constraint.CapacityConstraint;
import com.ridebatching.batching.constraint.DetourConstraint;
import com.ridebatching.batching.constraint.PickupSlaConstraint;
import com.ridebatching.batching.constraint.CapacityConstraint.CapacityRejectionDetails;
import com.ridebatching.batching.constraint.DetourConstraint.DetourRejectionDetails;
import com.ridebatching.batching.constraint.PickupSlaConstraint.PickupSlaRejectionDetails;
import com.ridebatching.batching.evaluator.TripFeasibilityEvaluator.EvaluationTrace;
import com.ridebatching.fleet.Vehicle;
import com.ridebatching.fleet.Capacity;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.TripStop;
import com.ridebatching.trip.TripStopType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripFeasibilityEvaluatorTest {

    private CapacityConstraint capacityConstraint;
    private PickupSlaConstraint pickupSlaConstraint;
    private DetourConstraint detourConstraint;
    private TripFeasibilityEvaluator evaluator;

    @BeforeEach
    void setUp() {
        capacityConstraint = mock(CapacityConstraint.class);
        pickupSlaConstraint = mock(PickupSlaConstraint.class);
        detourConstraint = mock(DetourConstraint.class);

        evaluator = new TripFeasibilityEvaluator(capacityConstraint, pickupSlaConstraint, detourConstraint, new SimpleMeterRegistry());

        // Default mock responses (all feasible)
        when(capacityConstraint.evaluate(any(), anyInt())).thenReturn(FeasibilityResult.feasible());
        when(pickupSlaConstraint.evaluate(any(), any(), any())).thenReturn(FeasibilityResult.feasible());
        when(detourConstraint.evaluate(any(), any())).thenReturn(FeasibilityResult.feasible());
    }

    private TripStop mockStop(UUID bookingId, TripStopType type) {
        return new TripStop(UUID.randomUUID(), bookingId, type, new GeoPoint(0, 0));
    }

    private Vehicle mockVehicle() {
        Vehicle v = mock(Vehicle.class);
        when(v.getCapacity()).thenReturn(new Capacity(4));
        return v;
    }

    @Test
    void shouldAcceptValidTripAndRecordTrace() {
        UUID b1 = UUID.randomUUID();
        List<TripStop> route = List.of(
                mockStop(b1, TripStopType.PICKUP),
                mockStop(b1, TripStopType.DROPOFF)
        );

        EvaluationTrace trace = evaluator.evaluate(route, Map.of(), mockVehicle(), Instant.now(), b1);

        assertThat(trace.isFeasible()).isTrue();
        assertThat(trace.candidateBookingId()).isEqualTo(b1);
        assertThat(trace.constraintsEvaluated()).containsExactly("STRUCTURAL", "CAPACITY", "PICKUP_SLA", "DETOUR");
        assertThat(trace.rejectionDetails()).isNull();
    }

    @Test
    void shouldRejectWhenStructuralCheckFails_DropoffBeforePickup() {
        UUID b1 = UUID.randomUUID();
        List<TripStop> route = List.of(
                mockStop(b1, TripStopType.DROPOFF), // Invalid
                mockStop(b1, TripStopType.PICKUP)
        );

        EvaluationTrace trace = evaluator.evaluate(route, Map.of(), mockVehicle(), Instant.now(), b1);

        assertThat(trace.isFeasible()).isFalse();
        assertThat(trace.isRejectedBy("STRUCTURAL_DROPOFF_BEFORE_PICKUP")).isTrue();
        assertThat(trace.constraintsEvaluated()).containsExactly("STRUCTURAL"); // Fails early
    }

    @Test
    void shouldRejectWhenStructuralCheckFails_DuplicatePickup() {
        UUID b1 = UUID.randomUUID();
        List<TripStop> route = List.of(
                mockStop(b1, TripStopType.PICKUP),
                mockStop(b1, TripStopType.PICKUP), // Invalid
                mockStop(b1, TripStopType.DROPOFF)
        );

        EvaluationTrace trace = evaluator.evaluate(route, Map.of(), mockVehicle(), Instant.now(), b1);

        assertThat(trace.isFeasible()).isFalse();
        assertThat(trace.isRejectedBy("STRUCTURAL_DUPLICATE_PICKUP")).isTrue();
    }

    @Test
    void shouldRejectWhenStructuralCheckFails_MissingDropoff() {
        UUID b1 = UUID.randomUUID();
        List<TripStop> route = List.of(
                mockStop(b1, TripStopType.PICKUP)
                // Missing dropoff
        );

        EvaluationTrace trace = evaluator.evaluate(route, Map.of(), mockVehicle(), Instant.now(), b1);

        assertThat(trace.isFeasible()).isFalse();
        assertThat(trace.isRejectedBy("STRUCTURAL_MISSING_DROPOFF")).isTrue();
    }

    @Test
    void shouldRejectWhenCapacityConstraintFails() {
        UUID b1 = UUID.randomUUID();
        List<TripStop> route = List.of(
                mockStop(b1, TripStopType.PICKUP),
                mockStop(b1, TripStopType.DROPOFF)
        );
        Vehicle vehicle = mockVehicle();

        when(capacityConstraint.evaluate(eq(vehicle), eq(1)))
            .thenReturn(FeasibilityResult.infeasible("Over capacity", new CapacityRejectionDetails("CAPACITY", null)));

        EvaluationTrace trace = evaluator.evaluate(route, Map.of(), vehicle, Instant.now(), b1);

        assertThat(trace.isFeasible()).isFalse();
        assertThat(trace.isRejectedBy("CAPACITY")).isTrue();
        assertThat(trace.constraintsEvaluated()).containsExactly("STRUCTURAL", "CAPACITY");
    }

    @Test
    void shouldRejectWhenPickupSlaConstraintFails() {
        UUID b1 = UUID.randomUUID();
        List<TripStop> route = List.of(
                mockStop(b1, TripStopType.PICKUP),
                mockStop(b1, TripStopType.DROPOFF)
        );

        when(pickupSlaConstraint.evaluate(any(), any(), any()))
            .thenReturn(FeasibilityResult.infeasible("SLA Miss", new PickupSlaRejectionDetails("PICKUP_SLA", b1, Instant.now(), Instant.now())));

        EvaluationTrace trace = evaluator.evaluate(route, Map.of(), mockVehicle(), Instant.now(), b1);

        assertThat(trace.isFeasible()).isFalse();
        assertThat(trace.isRejectedBy("PICKUP_SLA")).isTrue();
        assertThat(trace.constraintsEvaluated()).containsExactly("STRUCTURAL", "CAPACITY", "PICKUP_SLA");
    }

    @Test
    void shouldRejectWhenDetourConstraintFails() {
        UUID b1 = UUID.randomUUID();
        List<TripStop> route = List.of(
                mockStop(b1, TripStopType.PICKUP),
                mockStop(b1, TripStopType.DROPOFF)
        );

        when(detourConstraint.evaluate(any(), any()))
            .thenReturn(FeasibilityResult.infeasible("Detour Miss", new DetourRejectionDetails("RELATIVE_DETOUR", b1, Duration.ofMinutes(10), Duration.ofMinutes(20), "RELATIVE")));

        EvaluationTrace trace = evaluator.evaluate(route, Map.of(), mockVehicle(), Instant.now(), b1);

        assertThat(trace.isFeasible()).isFalse();
        assertThat(trace.isRejectedBy("RELATIVE_DETOUR")).isTrue();
        assertThat(trace.constraintsEvaluated()).containsExactly("STRUCTURAL", "CAPACITY", "PICKUP_SLA", "DETOUR");
    }
}
