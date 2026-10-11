package com.ridebatching.dispatch;

import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.TripStatus;
import com.ridebatching.trip.TripStopType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DispatchView(UUID tripId, TripStatus tripStatus, UUID assignedDriverId,
                           int peakPassengers, DispatchOutcome outcome, UUID activeOfferId,
                           int attemptsUsed, int attemptLimit, List<Offer> offers, List<Stop> stops,
                           @Schema(description = "Current configured planning seat limit; not a historical snapshot or assigned driver's capacity.")
                           int planningCapacity,
                           @Schema(description = "Distinct current route booking IDs, in first-stop order. Cancelled riders removed from the route are absent.")
                           List<UUID> bookingIds,
                           @Schema(description = "Optional read-only hypothetical comparison, populated only when candidateBookingId is supplied on single-trip GET. Not persisted decision history.", nullable = true)
                           InsertionInspection insertionCheck) {
    public record Offer(UUID id, UUID driverId, DriverOfferStatus status,
                        Instant createdAt, Instant expiresAt, Instant updatedAt) {}
    public record Stop(int sequence, UUID bookingId, TripStopType type, GeoPoint location,
                       @Schema(description = "Persisted route estimate, not actual arrival or a live recalculation. Null on legacy stops without estimates.", nullable = true)
                       Instant estimatedArrivalAt,
                       @Schema(description = "Stored pickup promise for PICKUP stops; null for DROPOFF stops.", nullable = true)
                       Instant pickupSlaDeadline,
                       @Schema(description = "Whether the stored pickup estimate meets its deadline, including equality. Null for dropoffs or unknown estimates.", nullable = true)
                       Boolean withinPickupSla) {}
}
