package com.ridebatching.booking;

import com.ridebatching.geo.GeoPoint;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    private UUID tripId;

    private double pickupLatitude;
    private double pickupLongitude;
    private double dropoffLatitude;
    private double dropoffLongitude;
    
    private Instant slotTimestamp;
    
    @Enumerated(EnumType.STRING)
    private BookingStatus status;
    
    private Instant pickupSlaDeadline;
    private Instant createdAt;
    
    @jakarta.persistence.Column(name = "deduplication_key", nullable = false)
    private String deduplicationKey;

    protected Booking() {
        // JPA required
    }

    public Booking(UUID id, Employee employee, 
                   GeoPoint pickup, GeoPoint dropoff, 
                   Instant slotTimestamp, BookingStatus status, 
                   Instant pickupSlaDeadline, Instant createdAt,
                   String deduplicationKey) {
        this.id = id;
        this.employee = employee;
        this.pickupLatitude = pickup.latitude();
        this.pickupLongitude = pickup.longitude();
        this.dropoffLatitude = dropoff.latitude();
        this.dropoffLongitude = dropoff.longitude();
        this.slotTimestamp = slotTimestamp;
        this.status = status;
        this.pickupSlaDeadline = pickupSlaDeadline;
        this.createdAt = createdAt;
        this.deduplicationKey = deduplicationKey;
    }

    public UUID getId() { return id; }
    public Employee getEmployee() { return employee; }
    public UUID getTripId() { return tripId; }
    
    public GeoPoint getPickupLocation() {
        return new GeoPoint(pickupLatitude, pickupLongitude);
    }
    
    public GeoPoint getDropoffLocation() {
        return new GeoPoint(dropoffLatitude, dropoffLongitude);
    }
    
    public Instant getSlotTimestamp() { return slotTimestamp; }
    public BookingStatus getStatus() { return status; }
    public Instant getPickupSlaDeadline() { return pickupSlaDeadline; }
    public Instant getCreatedAt() { return createdAt; }
    public String getDeduplicationKey() { return deduplicationKey; }

    public void setTripId(UUID tripId) {
        this.tripId = tripId;
    }

    public void cancel() {
        if (this.status == BookingStatus.CANCELLED) {
            return; // Idempotent
        }
        if (this.status != BookingStatus.PENDING && this.status != BookingStatus.CONFIRMED) {
            throw new com.ridebatching.api.DomainConflictException("Booking cannot be cancelled from status: " + this.status);
        }
        
        this.status = BookingStatus.CANCELLED;
    }

    /** Confirmation records a planned route, not driver acceptance or physical pickup. */
    public void confirm() {
        requireStatus(BookingStatus.PENDING);
        if (tripId == null) throw new com.ridebatching.api.DomainConflictException("Confirmation requires a planned trip");
        status = BookingStatus.CONFIRMED;
    }

    public void pickUp() {
        requireStatus(BookingStatus.CONFIRMED);
        status = BookingStatus.PICKED_UP;
    }

    public void dropOff() {
        requireStatus(BookingStatus.PICKED_UP);
        status = BookingStatus.DROPPED;
    }

    private void requireStatus(BookingStatus expected) {
        if (status != expected) throw new com.ridebatching.api.DomainConflictException(
                "Booking transition requires " + expected + ", current status is " + status);
    }
}
