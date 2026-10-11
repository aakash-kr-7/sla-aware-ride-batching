package com.ridebatching.dispatch;

import com.ridebatching.api.DomainConflictException;
import com.ridebatching.batching.BatchPlanner;
import com.ridebatching.batching.FeasibilityResult;
import com.ridebatching.batching.constraint.DetourConstraint;
import com.ridebatching.batching.constraint.PickupSlaConstraint;
import com.ridebatching.batching.evaluator.TripFeasibilityEvaluator;
import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.fleet.Vehicle;
import com.ridebatching.geo.TravelTimeEstimator;
import com.ridebatching.trip.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.Duration;
import java.util.*;

/** Reuses the insertion search and rules without retaining decisions or changing domain state. */
@Component
public class TripInsertionInspector {
    private final RouteInsertionPlanner insertion;
    private final TripFeasibilityEvaluator evaluator;
    private final BatchPlanner planner;
    private final TravelTimeEstimator estimator;
    public TripInsertionInspector(RouteInsertionPlanner insertion, TripFeasibilityEvaluator evaluator,
                                  BatchPlanner planner, TravelTimeEstimator estimator) {
        this.insertion = insertion; this.evaluator = evaluator; this.planner = planner; this.estimator = estimator;
    }

    public InsertionInspection inspect(Trip trip, Booking candidate, Map<UUID, Booking> currentRiders, Instant now) {
        if (trip.getStatus() != TripStatus.PLANNED)
            throw new DomainConflictException("Insertion inspection requires a planned trip");
        if (currentRiders.containsKey(candidate.getId()) ||
                (candidate.getStatus() != BookingStatus.PENDING && candidate.getStatus() != BookingStatus.CONFIRMED))
            throw new IllegalArgumentException("Candidate must be a pending or confirmed booking outside this route");
        Map<UUID, Booking> riders = new LinkedHashMap<>();
        trip.getStops().forEach(stop -> riders.put(stop.getBookingId(), currentRiders.get(stop.getBookingId())));
        riders.put(candidate.getId(), candidate);
        Instant start = now.isAfter(trip.getWindowStartTime()) ? now : trip.getWindowStartTime();
        Vehicle vehicle = new Vehicle(trip.getId(), planner.getDefaultVehicleCapacity());
        Map<String, InsertionInspection.RejectionReason> examples = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        int[] evaluated = {0};
        List<TripStop> best = insertion.planBestInsertion(trip.getStops(), candidate, route -> {
            var trace = evaluator.inspect(route, riders, vehicle, start, candidate.getId());
            evaluated[0]++;
            if (!trace.isFeasible()) {
                String code = trace.rejectionDetails().constraintCode();
                counts.merge(code, 1, Integer::sum);
                var previous = examples.get(code);
                var sla = trace.rejectionDetails() instanceof PickupSlaConstraint.PickupSlaRejectionDetails s ? s : null;
                // The closest missed promise is more useful than an arbitrary hours-late route.
                if (previous == null || (sla != null && Duration.between(sla.deadline(), sla.estimatedPickup())
                        .compareTo(Duration.between(previous.pickupSlaDeadline(), previous.estimatedPickupAt())) < 0))
                    examples.put(code, example(trace.rejectionDetails(), route));
            }
            return trace.isFeasible();
        });
        List<InsertionInspection.RejectionReason> reasons = examples.entrySet().stream().map(entry -> {
            var e = entry.getValue();
            return new InsertionInspection.RejectionReason(e.constraintCode(), counts.get(entry.getKey()),
                    e.affectedBooking(), e.examplePeakPassengers(), e.estimatedPickupAt(), e.pickupSlaDeadline(),
                    e.directTravelSeconds(), e.sharedTravelSeconds());
        }).toList();
        List<DispatchView.Stop> proposed = new ArrayList<>();
        Instant arrival = start;
        for (int i = 0; i < best.size(); i++) {
            TripStop stop = best.get(i);
            if (i > 0) arrival = arrival.plus(estimator.estimateTravelTime(best.get(i-1).getLocation(), stop.getLocation()));
            Instant deadline = stop.getType() == TripStopType.PICKUP ? riders.get(stop.getBookingId()).getPickupSlaDeadline() : null;
            proposed.add(new DispatchView.Stop(i + 1, stop.getBookingId(), stop.getType(), stop.getLocation(), arrival,
                    deadline, deadline == null ? null : !arrival.isAfter(deadline)));
        }
        return new InsertionInspection(candidate.getId(), now, start, "UNASSIGNED_CURRENT_TIME", !best.isEmpty(),
                evaluated[0], reasons, List.copyOf(proposed));
    }

    private InsertionInspection.RejectionReason example(FeasibilityResult.RejectionDetails details, List<TripStop> route) {
        int occupancy = 0, peak = 0;
        for (TripStop stop : route) {
            occupancy += stop.getType() == TripStopType.PICKUP ? 1 : -1;
            peak = Math.max(peak, occupancy);
        }
        var sla = details instanceof PickupSlaConstraint.PickupSlaRejectionDetails s ? s : null;
        var detour = details instanceof DetourConstraint.DetourRejectionDetails d ? d : null;
        return new InsertionInspection.RejectionReason(details.constraintCode(), 0, details.affectedBooking(), peak,
                sla == null ? null : sla.estimatedPickup(), sla == null ? null : sla.deadline(),
                detour == null ? null : detour.directTravelTime().getSeconds(),
                detour == null ? null : detour.sharedTravelTime().getSeconds());
    }
}
