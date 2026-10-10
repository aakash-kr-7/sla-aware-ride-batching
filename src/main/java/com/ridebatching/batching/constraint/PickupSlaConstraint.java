package com.ridebatching.batching.constraint;

import com.ridebatching.batching.FeasibilityResult;
import com.ridebatching.booking.Booking;
import com.ridebatching.geo.TravelTimeEstimator;
import com.ridebatching.trip.TripStop;
import com.ridebatching.trip.TripStopType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PickupSlaConstraint {

    private final TravelTimeEstimator travelTimeEstimator;

    public record PickupSlaRejectionDetails(String constraintCode, UUID affectedBooking, Instant estimatedPickup, Instant deadline) implements FeasibilityResult.RejectionDetails {}

    public PickupSlaConstraint(TravelTimeEstimator travelTimeEstimator) {
        this.travelTimeEstimator = travelTimeEstimator;
    }

    /**
     * Evaluates whether a proposed trip route violates any passenger's pickup SLA.
     * 
     * @param route The proposed sequence of stops
     * @param routeStartTime The timestamp when the trip is expected to start (e.g. batching window end or current time)
     * @param bookings Map of bookingId to Booking for all passengers in the route
     * @return FeasibilityResult indicating whether the constraint holds
     */
    public FeasibilityResult evaluate(List<TripStop> route, Instant routeStartTime, Map<UUID, Booking> bookings) {
        if (route.isEmpty()) {
            return FeasibilityResult.feasible();
        }

        Instant currentEstimatedTime = routeStartTime;
        
        for (int i = 0; i < route.size(); i++) {
            TripStop stop = route.get(i);
            
            if (i > 0) {
                TripStop previousStop = route.get(i - 1);
                Duration travelDuration = travelTimeEstimator.estimateTravelTime(previousStop.getLocation(), stop.getLocation());
                currentEstimatedTime = currentEstimatedTime.plus(travelDuration);
            }

            if (stop.getType() == TripStopType.PICKUP) {
                Booking booking = bookings.get(stop.getBookingId());
                if (booking == null) {
                    throw new IllegalStateException("Booking not found for stop: " + stop.getBookingId());
                }

                Instant deadline = booking.getPickupSlaDeadline();
                
                // If estimated pickup time is strictly greater than the deadline, SLA is missed
                if (currentEstimatedTime.isAfter(deadline)) {
                    return FeasibilityResult.infeasible(
                        "Pickup SLA would be exceeded",
                        new PickupSlaRejectionDetails(
                            "PICKUP_SLA",
                            booking.getId(),
                            currentEstimatedTime,
                            deadline
                        )
                    );
                }
            }
        }
        
        return FeasibilityResult.feasible();
    }
}
