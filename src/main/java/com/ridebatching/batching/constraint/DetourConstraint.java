package com.ridebatching.batching.constraint;

import com.ridebatching.batching.FeasibilityResult;
import com.ridebatching.booking.Booking;
import com.ridebatching.geo.TravelTimeEstimator;
import com.ridebatching.trip.TripStop;
import com.ridebatching.trip.TripStopType;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DetourConstraint {

    public record DetourRejectionDetails(
        String constraintCode, 
        UUID affectedBooking, 
        Duration directTravelTime, 
        Duration sharedTravelTime, 
        String metric
    ) implements FeasibilityResult.RejectionDetails {}

    private final TravelTimeEstimator travelTimeEstimator;
    private final double maxRelativeDetourRatio;
    private final Duration maxAbsoluteDetour;

    public DetourConstraint(TravelTimeEstimator travelTimeEstimator, double maxRelativeDetourRatio, Duration maxAbsoluteDetour) {
        this.travelTimeEstimator = travelTimeEstimator;
        this.maxRelativeDetourRatio = maxRelativeDetourRatio;
        this.maxAbsoluteDetour = maxAbsoluteDetour;
    }

    /**
     * Evaluates whether a proposed trip route violates any passenger's detour bounds.
     * 
     * @param route The proposed sequence of stops
     * @param bookings Map of bookingId to Booking for all passengers in the route
     * @return FeasibilityResult indicating whether the constraint holds
     */
    public FeasibilityResult evaluate(List<TripStop> route, Map<UUID, Booking> bookings) {
        if (route.isEmpty()) {
            return FeasibilityResult.feasible();
        }

        Map<UUID, Duration> pickupTimes = new HashMap<>();
        Map<UUID, Duration> dropoffTimes = new HashMap<>();

        Duration currentTravelTime = Duration.ZERO;
        
        for (int i = 0; i < route.size(); i++) {
            TripStop stop = route.get(i);
            
            if (i > 0) {
                TripStop previousStop = route.get(i - 1);
                Duration segmentTime = travelTimeEstimator.estimateTravelTime(previousStop.getLocation(), stop.getLocation());
                currentTravelTime = currentTravelTime.plus(segmentTime);
            }

            if (stop.getType() == TripStopType.PICKUP) {
                pickupTimes.put(stop.getBookingId(), currentTravelTime);
            } else if (stop.getType() == TripStopType.DROPOFF) {
                dropoffTimes.put(stop.getBookingId(), currentTravelTime);
            }
        }

        for (Map.Entry<UUID, Booking> entry : bookings.entrySet()) {
            UUID bookingId = entry.getKey();
            Booking booking = entry.getValue();

            Duration pickupTime = pickupTimes.get(bookingId);
            Duration dropoffTime = dropoffTimes.get(bookingId);

            if (pickupTime == null || dropoffTime == null) {
                throw new IllegalStateException("Missing pickup or dropoff for booking in route: " + bookingId);
            }

            Duration sharedTravelTime = dropoffTime.minus(pickupTime);
            Duration directTravelTime = travelTimeEstimator.estimateTravelTime(booking.getPickupLocation(), booking.getDropoffLocation());

            Duration detourTime = sharedTravelTime.minus(directTravelTime);

            // Absolute detour constraint
            if (detourTime.compareTo(maxAbsoluteDetour) > 0) {
                return FeasibilityResult.infeasible(
                    "Absolute detour exceeded",
                    new DetourRejectionDetails("ABSOLUTE_DETOUR", bookingId, directTravelTime, sharedTravelTime, "ABSOLUTE")
                );
            }

            // Relative detour constraint
            if (!directTravelTime.isZero()) {
                long allowedDetourMillis = (long) (directTravelTime.toMillis() * maxRelativeDetourRatio);
                if (detourTime.toMillis() > allowedDetourMillis) {
                    return FeasibilityResult.infeasible(
                        "Relative detour exceeded",
                        new DetourRejectionDetails("RELATIVE_DETOUR", bookingId, directTravelTime, sharedTravelTime, "RELATIVE")
                    );
                }
            } else if (!detourTime.isZero()) {
                // If direct travel is zero but detour is non-zero, it theoretically exceeds any relative ratio
                return FeasibilityResult.infeasible(
                    "Relative detour exceeded (direct time is zero)",
                    new DetourRejectionDetails("RELATIVE_DETOUR", bookingId, directTravelTime, sharedTravelTime, "RELATIVE")
                );
            }
        }

        return FeasibilityResult.feasible();
    }
}
