package com.ridebatching.batching.evaluator;

import com.ridebatching.batching.FeasibilityResult;
import com.ridebatching.batching.constraint.CapacityConstraint;
import com.ridebatching.batching.constraint.DetourConstraint;
import com.ridebatching.batching.constraint.PickupSlaConstraint;
import com.ridebatching.booking.Booking;
import com.ridebatching.fleet.Vehicle;
import com.ridebatching.trip.TripStop;
import com.ridebatching.trip.TripStopType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Composes batching rules and constraints to evaluate trip insertion candidates.
 * Emits a structured trace for every evaluation to explain decisions and reason codes.
 */
public class TripFeasibilityEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TripFeasibilityEvaluator.class);

    private final CapacityConstraint capacityConstraint;
    private final PickupSlaConstraint pickupSlaConstraint;
    private final DetourConstraint detourConstraint;
    private final MeterRegistry meterRegistry;

    public TripFeasibilityEvaluator(
            CapacityConstraint capacityConstraint,
            PickupSlaConstraint pickupSlaConstraint,
            DetourConstraint detourConstraint,
            MeterRegistry meterRegistry) {
        this.capacityConstraint = capacityConstraint;
        this.pickupSlaConstraint = pickupSlaConstraint;
        this.detourConstraint = detourConstraint;
        this.meterRegistry = meterRegistry;
    }

    /**
     * A lightweight decision trace containing the evaluation path and result.
     * This avoids massive application logs while maintaining explainability of the combinatorial search.
     */
    public record EvaluationTrace(
            UUID traceId,
            List<TripStop> candidateRoute,
            UUID candidateBookingId,
            List<String> constraintsEvaluated,
            boolean isFeasible,
            FeasibilityResult.RejectionDetails rejectionDetails
    ) {
        public boolean isRejectedBy(String code) {
            return !isFeasible && rejectionDetails != null && code.equals(rejectionDetails.constraintCode());
        }
    }

    public record StructuralRejectionDetails(String constraintCode, UUID affectedBooking) implements FeasibilityResult.RejectionDetails {}

    /**
     * Evaluates a candidate route insertion.
     * 
     * @param route The ordered stops of the proposed route
     * @param bookings The bookings participating in this route
     * @param vehicle The assigned vehicle (can be null if unassigned, bypassing capacity check)
     * @param start The proposed start time of the route
     * @param candidate The booking ID being considered for insertion
     * @return EvaluationTrace containing the outcome and evaluation path
     */
    public EvaluationTrace evaluate(List<TripStop> route, Map<UUID, Booking> bookings,
                                    Vehicle vehicle, Instant start, UUID candidate) {
        return evaluate(route, bookings, vehicle, start, candidate, true);
    }

    /** Same constraints, without operational counters or decision logs for a read-only diagnostic. */
    public EvaluationTrace inspect(List<TripStop> route, Map<UUID, Booking> bookings,
                                   Vehicle vehicle, Instant start, UUID candidate) {
        return evaluate(route, bookings, vehicle, start, candidate, false);
    }

    private EvaluationTrace evaluate(List<TripStop> candidateRoute, Map<UUID, Booking> currentBookings,
                                     Vehicle vehicle, Instant routeStartTime, UUID candidateBookingId,
                                     boolean emitEvents) {
        UUID traceId = UUID.randomUUID();
        List<String> evaluated = new ArrayList<>();
        
        // 1. Structural checks and capacity calculation
        evaluated.add("STRUCTURAL");
        int peakPassengers = 0;
        int currentPassengers = 0;
        Set<UUID> onboard = new HashSet<>();
        Set<UUID> pickedUp = new HashSet<>();
        
        for (TripStop stop : candidateRoute) {
            if (stop.getType() == TripStopType.PICKUP) {
                if (!pickedUp.add(stop.getBookingId())) {
                    return logAndReturn(traceId, candidateRoute, candidateBookingId, evaluated, emitEvents, FeasibilityResult.infeasible("Duplicate pickup", new StructuralRejectionDetails("STRUCTURAL_DUPLICATE_PICKUP", stop.getBookingId())));
                }
                onboard.add(stop.getBookingId());
                currentPassengers++;
            } else if (stop.getType() == TripStopType.DROPOFF) {
                if (!onboard.remove(stop.getBookingId())) {
                    return logAndReturn(traceId, candidateRoute, candidateBookingId, evaluated, emitEvents, FeasibilityResult.infeasible("Dropoff before pickup", new StructuralRejectionDetails("STRUCTURAL_DROPOFF_BEFORE_PICKUP", stop.getBookingId())));
                }
                currentPassengers--;
            }
            if (currentPassengers > peakPassengers) {
                peakPassengers = currentPassengers;
            }
        }
        
        if (!onboard.isEmpty()) {
            UUID missing = onboard.iterator().next();
            return logAndReturn(traceId, candidateRoute, candidateBookingId, evaluated, emitEvents, FeasibilityResult.infeasible("Missing dropoff", new StructuralRejectionDetails("STRUCTURAL_MISSING_DROPOFF", missing)));
        }

        // 2. Capacity Constraint
        if (vehicle != null) {
            evaluated.add("CAPACITY");
            FeasibilityResult capacityResult = capacityConstraint.evaluate(vehicle, peakPassengers);
            if (!capacityResult.isFeasible()) {
                return logAndReturn(traceId, candidateRoute, candidateBookingId, evaluated, emitEvents, capacityResult);
            }
        }

        // 3. Pickup SLA Constraint
        evaluated.add("PICKUP_SLA");
        FeasibilityResult slaResult = pickupSlaConstraint.evaluate(candidateRoute, routeStartTime, currentBookings);
        if (!slaResult.isFeasible()) {
            if (emitEvents) meterRegistry.counter("batching.sla_rejections").increment();
            return logAndReturn(traceId, candidateRoute, candidateBookingId, evaluated, emitEvents, slaResult);
        }

        // 4. Detour Constraint
        evaluated.add("DETOUR");
        FeasibilityResult detourResult = detourConstraint.evaluate(candidateRoute, currentBookings);
        if (!detourResult.isFeasible()) {
            if (emitEvents) meterRegistry.counter("batching.detour_rejections").increment();
            return logAndReturn(traceId, candidateRoute, candidateBookingId, evaluated, emitEvents, detourResult);
        }

        return logAndReturn(traceId, candidateRoute, candidateBookingId, evaluated, emitEvents, FeasibilityResult.feasible());
    }

    private EvaluationTrace logAndReturn(
            UUID traceId, 
            List<TripStop> candidateRoute, 
            UUID candidateBookingId, 
            List<String> evaluated, 
            boolean emitEvents, FeasibilityResult result) {
        
        EvaluationTrace trace = new EvaluationTrace(
            traceId, 
            candidateRoute, 
            candidateBookingId, 
            evaluated, 
            result.isFeasible(), 
            result.rejectionDetails()
        );

        if (emitEvents && log.isDebugEnabled()) {
            // Keep logging structured but concise. Avoid logging full list of stops to prevent noise.
            log.debug("Batching Decision [traceId={}, candidateBooking={}, feasible={}, constraintsEvaluated={}, reasonCode={}]", 
                traceId, 
                candidateBookingId, 
                result.isFeasible(), 
                evaluated.size(), 
                result.isFeasible() ? "NONE" : (result.rejectionDetails() != null ? result.rejectionDetails().constraintCode() : "UNKNOWN")
            );
        }

        return trace;
    }
}
