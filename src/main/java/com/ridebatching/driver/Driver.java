package com.ridebatching.driver;

import com.ridebatching.geo.GeoPoint;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "drivers")
public class Driver {

    @Id
    private UUID id;

    private String name;

    @Enumerated(EnumType.STRING)
    private DriverStatus status;

    private double latitude;
    private double longitude;

    private UUID activeTripId;

    private int vehicleCapacity;

    private Instant updatedAt;
    @jakarta.persistence.Version
    private Long version;

    protected Driver() {
        // JPA required
    }

    public Driver(UUID id, String name, int vehicleCapacity) {
        this.id = id;
        this.name = name;
        this.status = DriverStatus.OFFLINE;
        this.vehicleCapacity = vehicleCapacity;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public DriverStatus getStatus() { return status; }
    public UUID getActiveTripId() { return activeTripId; }
    public int getVehicleCapacity() { return vehicleCapacity; }

    public GeoPoint getLocation() {
        return new GeoPoint(latitude, longitude);
    }

    public void updateLocation(GeoPoint location) {
        this.latitude = location.latitude();
        this.longitude = location.longitude();
        this.updatedAt = Instant.now();
    }

    public void goOnline() {
        if (this.status != DriverStatus.OFFLINE) {
            throw new com.ridebatching.api.DomainConflictException("Driver must be OFFLINE to go ONLINE");
        }
        this.status = DriverStatus.AVAILABLE;
        this.updatedAt = Instant.now();
    }

    public void goOffline() {
        if (this.activeTripId != null) {
            throw new com.ridebatching.api.DomainConflictException("Cannot go OFFLINE while assigned to an active trip");
        }
        this.status = DriverStatus.OFFLINE;
        this.updatedAt = Instant.now();
    }

    public void startBreak() {
        if (this.status != DriverStatus.AVAILABLE) {
            throw new com.ridebatching.api.DomainConflictException("Driver must be AVAILABLE to start a break");
        }
        if (this.activeTripId != null) {
            throw new com.ridebatching.api.DomainConflictException("Cannot start break while assigned to an active trip");
        }
        this.status = DriverStatus.ON_BREAK;
        this.updatedAt = Instant.now();
    }

    public void endBreak() {
        if (this.status != DriverStatus.ON_BREAK) {
            throw new com.ridebatching.api.DomainConflictException("Driver is not on a break");
        }
        this.status = DriverStatus.AVAILABLE;
        this.updatedAt = Instant.now();
    }

    public void assignTrip(UUID tripId) {
        if (tripId.equals(this.activeTripId)) {
            return;
        }
        if (this.status != DriverStatus.AVAILABLE) {
            throw new com.ridebatching.api.DomainConflictException("Driver must be AVAILABLE to be assigned a trip");
        }
        if (this.activeTripId != null) {
            throw new com.ridebatching.api.DomainConflictException("Driver is already assigned to a trip");
        }
        this.activeTripId = tripId;
        this.updatedAt = Instant.now();
    }

    public void completeTrip() {
        if (this.activeTripId == null) {
            throw new com.ridebatching.api.DomainConflictException("Driver has no active trip to complete");
        }
        this.activeTripId = null;
        this.updatedAt = Instant.now();
    }
}
