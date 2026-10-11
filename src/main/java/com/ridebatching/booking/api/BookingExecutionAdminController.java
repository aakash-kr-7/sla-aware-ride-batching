package com.ridebatching.booking.api;

import com.ridebatching.booking.BookingExecutionService;
import com.ridebatching.booking.dto.BookingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/bookings")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin (Execution simulation)", description = "Record the next pickup/dropoff for an accepted trip. Admin simulation uses its stored driver assignment; no driver identity or status payload is accepted. Follow the route order shown in dispatch inspection.")
public class BookingExecutionAdminController {
    private final BookingExecutionService execution;
    public BookingExecutionAdminController(BookingExecutionService execution) { this.execution = execution; }

    @PostMapping("/{bookingId}/pickup")
    @Operation(summary = "Record the next rider pickup", description = "Admin only; no body. Requires CONFIRMED booking, accepted active driver assignment and the next pickup in route order. First pickup starts trip IN_PROGRESS and closes cancellation for every remaining rider. Returns PICKED_UP; invalid or repeated transitions return 409 STATE_CONFLICT.")
    public BookingResponse pickUp(@PathVariable UUID bookingId) { return execution.pickUp(bookingId); }

    @PostMapping("/{bookingId}/dropoff")
    @Operation(summary = "Record the next rider dropoff", description = "Admin only; no body. Requires PICKED_UP and the next dropoff in route order. Returns DROPPED. Final dropoff completes the trip and releases its driver atomically. Invalid, skipped or repeated transitions return 409 STATE_CONFLICT. Estimates are not proof of actual arrival.")
    public BookingResponse dropOff(@PathVariable UUID bookingId) { return execution.dropOff(bookingId); }
}
