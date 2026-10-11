package com.ridebatching.trip;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "trips")
public class Trip {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    private TripStatus status;

    private UUID vehicleId;
    private UUID driverId;
    
    private Instant windowStartTime;
    private Instant createdAt;
    private Instant updatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "trip_id", nullable = false)
    @OrderBy("sequenceOrder ASC")
    private List<TripStop> stops = new ArrayList<>();

    protected Trip() {
        // JPA required
    }

    public Trip(UUID id, Instant windowStartTime, Instant createdAt) {
        this.id = id;
        this.status = TripStatus.PLANNED;
        this.windowStartTime = windowStartTime;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public TripStatus getStatus() { return status; }
    public UUID getVehicleId() { return vehicleId; }
    public UUID getDriverId() { return driverId; }
    public Instant getWindowStartTime() { return windowStartTime; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void requireBeforeExecution() {
        if (status != TripStatus.PLANNED && status != TripStatus.DISPATCHED)
            throw new com.ridebatching.api.DomainConflictException("Trip route can change only before the first pickup");
    }

    public void startExecution() {
        if (status != TripStatus.DISPATCHED)
            throw new com.ridebatching.api.DomainConflictException("Execution requires an accepted driver assignment");
        status = TripStatus.IN_PROGRESS;
        updatedAt = Instant.now();
    }

    public void completeExecution() {
        if (status != TripStatus.IN_PROGRESS)
            throw new com.ridebatching.api.DomainConflictException("Only an executing trip can complete");
        status = TripStatus.COMPLETED;
        updatedAt = Instant.now();
    }

    public void assignTo(UUID driverId) {
        if (this.status == TripStatus.DISPATCHED && this.driverId != null && this.driverId.equals(driverId)) {
            return;
        }
        if (this.status != TripStatus.PLANNED) {
            throw new com.ridebatching.api.DomainConflictException("Trip is already assigned or cannot be assigned. Status: " + this.status);
        }
        this.status = TripStatus.DISPATCHED;
        this.driverId = driverId;
        this.updatedAt = Instant.now();
    }

    /**
     * Returns an unmodifiable view of the current stops.
     */
    public List<TripStop> getStops() {
        return Collections.unmodifiableList(stops);
    }

    public int getPeakPassengers() {
        int onboard = 0;
        int peak = 0;
        for (TripStop stop : stops) {
            onboard += stop.getType() == TripStopType.PICKUP ? 1 : -1;
            peak = Math.max(peak, onboard);
        }
        return peak;
    }

    /**
     * Assigns a sequenced route to this trip.
     * Enforces the invariant that for every booking, the pickup stop 
     * must occur before its drop-off stop.
     */
    public void assignRoute(List<TripStop> route) {
        requireBeforeExecution();
        route = new ArrayList<>(route);
        validatePickupBeforeDropoff(route);
        
        this.stops.clear();
        for (int i = 0; i < route.size(); i++) {
            TripStop stop = route.get(i);
            stop.setSequenceOrder(i + 1);
            this.stops.add(stop);
        }
        
        this.updatedAt = Instant.now();
    }

    public void removeBooking(UUID bookingId) {
        requireBeforeExecution();
        boolean removed = false;
        List<TripStop> remainingStops = new ArrayList<>();
        for (TripStop stop : this.stops) {
            if (!stop.getBookingId().equals(bookingId)) {
                remainingStops.add(stop);
            } else {
                removed = true;
            }
        }
        
        if (!removed) {
            return;
        }
        
        if (remainingStops.isEmpty()) {
            this.status = TripStatus.CANCELLED;
            this.stops.clear();
            this.updatedAt = Instant.now();
        } else {
            assignRoute(remainingStops);
        }
    }

    private void validatePickupBeforeDropoff(List<TripStop> route) {
        Set<UUID> seenPickups = new HashSet<>();
        Set<UUID> seenDropoffs = new HashSet<>();
        
        for (TripStop stop : route) {
            UUID bookingId = stop.getBookingId();
            
            if (stop.getType() == TripStopType.PICKUP) {
                if (seenPickups.contains(bookingId)) {
                    throw new InvalidRouteException("Duplicate pickup for booking: " + bookingId);
                }
                seenPickups.add(bookingId);
                
            } else if (stop.getType() == TripStopType.DROPOFF) {
                if (!seenPickups.contains(bookingId)) {
                    throw new InvalidRouteException("Dropoff occurs before pickup for booking: " + bookingId);
                }
                if (seenDropoffs.contains(bookingId)) {
                    throw new InvalidRouteException("Duplicate dropoff for booking: " + bookingId);
                }
                seenDropoffs.add(bookingId);
            }
        }
        
        for (UUID pickupBookingId : seenPickups) {
            if (!seenDropoffs.contains(pickupBookingId)) {
                throw new InvalidRouteException("Missing dropoff for booking: " + pickupBookingId);
            }
        }
    }
}
