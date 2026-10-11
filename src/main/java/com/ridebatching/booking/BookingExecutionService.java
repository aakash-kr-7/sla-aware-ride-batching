package com.ridebatching.booking;

import com.ridebatching.api.DomainConflictException;
import com.ridebatching.booking.dto.BookingResponse;
import com.ridebatching.booking.exception.BookingNotFoundException;
import com.ridebatching.driver.Driver;
import com.ridebatching.driver.DriverRepository;
import com.ridebatching.trip.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Admin-simulated execution facts, serialized with pre-execution cancellation on the trip. */
@Service
public class BookingExecutionService {
    private final BookingRepository bookings;
    private final TripRepository trips;
    private final DriverRepository drivers;
    private final BookingService bookingService;
    @PersistenceContext private EntityManager entityManager;
    public BookingExecutionService(BookingRepository bookings, TripRepository trips,
                                    DriverRepository drivers, BookingService bookingService) {
        this.bookings = bookings; this.trips = trips; this.drivers = drivers; this.bookingService = bookingService;
    }

    @Transactional
    public BookingResponse pickUp(UUID bookingId) { return progress(bookingId, TripStopType.PICKUP); }

    @Transactional
    public BookingResponse dropOff(UUID bookingId) { return progress(bookingId, TripStopType.DROPOFF); }

    private BookingResponse progress(UUID bookingId, TripStopType action) {
        // Lock booking -> trip -> driver. Never lock other riders while holding the trip.
        Booking booking = bookings.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found with id: " + bookingId));
        entityManager.refresh(booking);
        if (booking.getTripId() == null) throw new DomainConflictException("Booking has no planned trip");
        Trip locked = trips.findByIdForUpdate(booking.getTripId()).orElseThrow();
        entityManager.detach(locked);
        Trip trip = trips.findById(booking.getTripId()).orElseThrow();
        if (trip.getStatus() != TripStatus.DISPATCHED && trip.getStatus() != TripStatus.IN_PROGRESS)
            throw new DomainConflictException("Execution requires an accepted driver and an unfinished trip");
        Map<UUID, Booking> riders = bookings.findAllById(trip.getStops().stream()
                .map(TripStop::getBookingId).distinct().toList()).stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
        TripStop next = trip.getStops().stream().filter(stop -> !completed(stop, riders.get(stop.getBookingId())))
                .findFirst().orElseThrow(() -> new DomainConflictException("Trip has no remaining stops"));
        if (!next.getBookingId().equals(bookingId) || next.getType() != action)
            throw new DomainConflictException("Execution must follow the next planned stop");
        Driver driver = drivers.findByIdForUpdate(trip.getDriverId()).orElseThrow();
        entityManager.refresh(driver);
        if (!trip.getId().equals(driver.getActiveTripId())) throw new DomainConflictException("Driver assignment is no longer active");
        if (action == TripStopType.PICKUP) {
            booking.pickUp();
            if (trip.getStatus() == TripStatus.DISPATCHED) trip.startExecution();
        } else {
            booking.dropOff();
            if (riders.values().stream().allMatch(rider -> rider.getStatus() == BookingStatus.DROPPED)) {
                trip.completeExecution();
                driver.completeTrip();
            }
        }
        return bookingService.getBooking(bookingId);
    }

    private boolean completed(TripStop stop, Booking rider) {
        if (rider == null || rider.getStatus() == BookingStatus.CANCELLED || rider.getStatus() == BookingStatus.PENDING)
            throw new DomainConflictException("Route contains a missing or unconfirmed booking");
        return stop.getType() == TripStopType.PICKUP
                ? rider.getStatus() == BookingStatus.PICKED_UP || rider.getStatus() == BookingStatus.DROPPED
                : rider.getStatus() == BookingStatus.DROPPED;
    }
}
