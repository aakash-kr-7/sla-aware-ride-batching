package com.ridebatching.booking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One employee's requested journey. Employee identity comes from X-Employee-Id; time slot, deadline and status are assigned by the server.", example = "{\"pickup\":{\"latitude\":12.9716,\"longitude\":77.5946},\"dropoff\":{\"latitude\":12.9352,\"longitude\":77.6245}}")
public record BookingRequest(
    @Schema(description = "Pickup coordinates; must lie inside a configured active service zone. Example coordinates require a matching configured zone.")
    @NotNull @Valid GeoPointDto pickup,
    @Schema(description = "Destination coordinates. Pickup-zone admission does not impose a dropoff-zone restriction.")
    @NotNull @Valid GeoPointDto dropoff
) {}
