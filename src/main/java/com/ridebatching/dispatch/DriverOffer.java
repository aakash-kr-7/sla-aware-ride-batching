package com.ridebatching.dispatch;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "driver_offers")
public class DriverOffer {

    @Id
    private UUID id;

    private UUID driverId;
    private UUID tripId;

    @Enumerated(EnumType.STRING)
    private DriverOfferStatus status;

    private Instant createdAt;
    private Instant updatedAt;

    protected DriverOffer() {
        // JPA
    }

    public DriverOffer(UUID id, UUID driverId, UUID tripId) {
        this.id = id;
        this.driverId = driverId;
        this.tripId = tripId;
        this.status = DriverOfferStatus.OFFERED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public UUID getDriverId() { return driverId; }
    public UUID getTripId() { return tripId; }
    public DriverOfferStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void accept() {
        if (this.status == DriverOfferStatus.ACCEPTED) {
            return;
        }
        if (this.status != DriverOfferStatus.OFFERED) {
            throw new com.ridebatching.api.DomainConflictException("Only OFFERED offers can be accepted");
        }
        this.status = DriverOfferStatus.ACCEPTED;
        this.updatedAt = Instant.now();
    }

    public void reject() {
        if (this.status == DriverOfferStatus.REJECTED) {
            return;
        }
        if (this.status != DriverOfferStatus.OFFERED) {
            throw new com.ridebatching.api.DomainConflictException("Only OFFERED offers can be rejected");
        }
        this.status = DriverOfferStatus.REJECTED;
        this.updatedAt = Instant.now();
    }

    public void expire() {
        if (this.status != DriverOfferStatus.OFFERED) {
            throw new com.ridebatching.api.DomainConflictException("Only OFFERED offers can expire");
        }
        this.status = DriverOfferStatus.EXPIRED;
        this.updatedAt = Instant.now();
    }
}
