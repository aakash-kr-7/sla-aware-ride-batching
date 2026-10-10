package com.ridebatching.trip;

import com.ridebatching.geo.GeoPoint;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

import java.util.UUID;

@Entity
@Table(name = "trip_stops")
public class TripStop {

    @Id
    private UUID id;
    
    @Column(name = "trip_id", insertable = false, updatable = false)
    private UUID tripId;
    
    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripStopType type;
    
    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;
    
    private double latitude;
    private double longitude;

    protected TripStop() {
        // JPA required
    }

    public TripStop(UUID id, UUID bookingId, TripStopType type, GeoPoint location) {
        this.id = id;
        this.bookingId = bookingId;
        this.type = type;
        this.latitude = location.latitude();
        this.longitude = location.longitude();
    }

    public UUID getId() { return id; }
    public UUID getTripId() { return tripId; }
    public UUID getBookingId() { return bookingId; }
    public TripStopType getType() { return type; }
    public int getSequenceOrder() { return sequenceOrder; }
    
    public GeoPoint getLocation() {
        return new GeoPoint(latitude, longitude);
    }

    // Package-private setters managed by the Trip aggregate root
    void setSequenceOrder(int sequenceOrder) {
        this.sequenceOrder = sequenceOrder;
    }
}
