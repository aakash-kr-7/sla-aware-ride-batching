package com.ridebatching.dispatch;

import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.TripStatus;
import com.ridebatching.trip.TripStopType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DispatchView(UUID tripId, TripStatus tripStatus, UUID assignedDriverId,
                           int peakPassengers, DispatchOutcome outcome, UUID activeOfferId,
                           int attemptsUsed, int attemptLimit, List<Offer> offers, List<Stop> stops) {
    public record Offer(UUID id, UUID driverId, DriverOfferStatus status,
                        Instant createdAt, Instant expiresAt, Instant updatedAt) {}
    public record Stop(int sequence, UUID bookingId, TripStopType type, GeoPoint location, Instant estimatedArrivalAt) {}
}
