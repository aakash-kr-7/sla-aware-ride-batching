package com.ridebatching.trip;

import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingRepository;
import com.ridebatching.escalation.BookingSlaEscalations;
import com.ridebatching.geo.TravelTimeEstimator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TripRouteTiming {
    private final TravelTimeEstimator estimator;
    private final BookingRepository bookings;
    private final BookingSlaEscalations escalations;
    private final Clock clock;
    public TripRouteTiming(TravelTimeEstimator estimator, BookingRepository bookings,
                           BookingSlaEscalations escalations, @Qualifier("batchingClock") Clock clock) {
        this.estimator = estimator; this.bookings = bookings; this.escalations = escalations; this.clock = clock;
    }

    /** Same first-pickup and leg/dwell assumptions as PickupSlaConstraint. */
    public void calculate(Trip trip, Instant start) {
        Instant arrival = start;
        TripStop previous = null;
        for (TripStop stop : trip.getStops()) {
            if (previous != null) arrival = arrival.plus(estimator.estimateTravelTime(previous.getLocation(), stop.getLocation()));
            stop.setEstimatedArrivalAt(arrival);
            previous = stop;
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void afterCancellation(Trip trip) {
        trip.requireBeforeExecution();
        Instant now = clock.instant();
        Instant start = now.isAfter(trip.getWindowStartTime()) ? now : trip.getWindowStartTime();
        calculate(trip, start);
        // Reads only: taking other booking locks while holding the trip would reverse the mutation lock order.
        Map<UUID, Booking> riders = bookings.findAllById(trip.getStops().stream()
                .map(TripStop::getBookingId).distinct().toList()).stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
        for (TripStop stop : trip.getStops()) {
            if (stop.getType() == TripStopType.PICKUP) {
                Booking rider = riders.get(stop.getBookingId());
                escalations.routeRisk(rider.getId(), stop.getEstimatedArrivalAt().isAfter(rider.getPickupSlaDeadline()), now);
            }
        }
    }

    public Instant now() { return clock.instant(); }
}
