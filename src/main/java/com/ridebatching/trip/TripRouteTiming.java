package com.ridebatching.trip;

import com.ridebatching.booking.Booking;
import com.ridebatching.api.DomainConflictException;
import com.ridebatching.driver.DriverRepository;
import com.ridebatching.batching.constraint.CapacityConstraint;
import com.ridebatching.batching.constraint.DetourConstraint;
import com.ridebatching.fleet.Vehicle;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
    private final DriverRepository drivers;
    private final CapacityConstraint capacity;
    private final DetourConstraint detour;
    private final int planningSeats;
    @PersistenceContext private EntityManager entityManager;
    public TripRouteTiming(TravelTimeEstimator estimator, BookingRepository bookings,
                           BookingSlaEscalations escalations, @Qualifier("batchingClock") Clock clock,
                           DriverRepository drivers, CapacityConstraint capacity, DetourConstraint detour,
                           @org.springframework.beans.factory.annotation.Value("${app.fleet.default-vehicle-capacity:4}") int planningSeats) {
        this.estimator = estimator; this.bookings = bookings; this.escalations = escalations; this.clock = clock;
        this.drivers = drivers; this.capacity = capacity; this.detour = detour; this.planningSeats = planningSeats;
    }

    public Instant firstPickupAt(Trip trip, com.ridebatching.geo.GeoPoint driverLocation, Instant now) {
        Instant departure = now.isAfter(trip.getWindowStartTime()) ? now : trip.getWindowStartTime();
        return departure.plus(estimator.estimateApproachTime(driverLocation, trip.getStops().get(0).getLocation()));
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
        int seats = planningSeats;
        if (trip.getStatus() == TripStatus.DISPATCHED) {
            var driver = drivers.findByIdForUpdate(trip.getDriverId()).orElseThrow();
            entityManager.refresh(driver);
            if (!trip.getId().equals(driver.getActiveTripId())) throw new DomainConflictException("Driver assignment is no longer active");
            seats = driver.getVehicleCapacity();
            start = firstPickupAt(trip, driver.getLocation(), now);
        }
        calculate(trip, start);
        // Reads only: taking other booking locks while holding the trip would reverse the mutation lock order.
        Map<UUID, Booking> riders = bookings.findAllById(trip.getStops().stream()
                .map(TripStop::getBookingId).distinct().toList()).stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
        var capacityCheck = capacity.evaluate(new Vehicle(trip.getId(), seats), trip.getPeakPassengers());
        var detourCheck = detour.evaluate(trip.getStops(), riders);
        if (!capacityCheck.isFeasible() || !detourCheck.isFeasible())
            throw new com.ridebatching.api.RouteInfeasibleException(!capacityCheck.isFeasible()
                    ? capacityCheck.rejectionDetails() : detourCheck.rejectionDetails());
        for (TripStop stop : trip.getStops()) {
            if (stop.getType() == TripStopType.PICKUP) {
                Booking rider = riders.get(stop.getBookingId());
                escalations.routeRisk(rider.getId(), stop.getEstimatedArrivalAt().isAfter(rider.getPickupSlaDeadline()), now);
            }
        }
    }

    public Instant now() { return clock.instant(); }
}
