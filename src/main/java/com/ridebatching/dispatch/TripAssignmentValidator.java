package com.ridebatching.dispatch;

import com.ridebatching.batching.evaluator.TripFeasibilityEvaluator;
import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.driver.Driver;
import com.ridebatching.fleet.Vehicle;
import com.ridebatching.trip.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/** Validates the stored route at the candidate driver's estimated first pickup, not its old planning time. */
@Component
public class TripAssignmentValidator {
    private final TripFeasibilityEvaluator evaluator;
    private final TripRouteTiming timing;
    public TripAssignmentValidator(TripFeasibilityEvaluator evaluator, TripRouteTiming timing) {
        this.evaluator = evaluator; this.timing = timing;
    }

    public record Check(Instant firstPickupAt, TripFeasibilityEvaluator.EvaluationTrace trace) {
        public boolean feasible() { return trace.isFeasible(); }
    }

    public Check evaluate(Trip trip, Driver driver, Map<UUID, Booking> riders, Instant now) {
        if (trip.getStops().isEmpty()) return invalid(trip, now, "ASSIGNMENT_EMPTY_ROUTE", null);
        for (TripStop stop : trip.getStops()) {
            Booking rider = riders.get(stop.getBookingId());
            if (rider == null || rider.getStatus() != BookingStatus.CONFIRMED || !trip.getId().equals(rider.getTripId()))
                return invalid(trip, now, "ASSIGNMENT_BOOKING_STATE", stop.getBookingId());
        }
        Instant firstPickup = timing.firstPickupAt(trip, driver.getLocation(), now);
        return new Check(firstPickup, evaluator.evaluate(trip.getStops(), riders,
                new Vehicle(driver.getId(), driver.getVehicleCapacity()), firstPickup, null));
    }

    private Check invalid(Trip trip, Instant now, String reason, UUID booking) {
        return new Check(now, new TripFeasibilityEvaluator.EvaluationTrace(UUID.randomUUID(), trip.getStops(),
                null, List.of("STRUCTURAL"), false,
                new TripFeasibilityEvaluator.StructuralRejectionDetails(reason, booking)));
    }
}
