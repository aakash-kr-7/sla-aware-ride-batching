package com.ridebatching.booking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record BookingRequest(
    @NotNull @Valid GeoPointDto pickup,
    @NotNull @Valid GeoPointDto dropoff
) {}
