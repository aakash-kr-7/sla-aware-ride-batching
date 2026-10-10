package com.ridebatching.booking.dto;

import com.ridebatching.booking.BookingStatus;
import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
    UUID id,
    UUID employeeId,
    GeoPointDto pickup,
    GeoPointDto dropoff,
    Instant slotTimestamp,
    BookingStatus status,
    Instant pickupSlaDeadline,
    UUID tripId,
    Instant createdAt
) {}
