package com.ridebatching.dispatch.api;

import com.ridebatching.dispatch.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/dispatch")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin (Dispatch simulation)", description = "Admins inspect trips and simulate the response of the driver stored on an offer. No driver identity or pickup is accepted in a request body. These are case-study simulation controls, not driver authentication or delivered notifications.")
public class DispatchAdminController {
    private final DispatchService dispatch;
    public DispatchAdminController(DispatchService dispatch) { this.dispatch = dispatch; }

    @GetMapping("/trips")
    @Operation(summary = "List trips, routes and dispatch outcomes", description = "Admin only. Newest trips first; page is zero-based and size must be 1..100 (default 20). Inspect peakPassengers, stop booking IDs, activeOfferId, attemptsUsed and offer history. Reading never advances dispatch.")
    public Page<DispatchView> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return dispatch.listTrips(page, size);
    }

    @GetMapping("/trips/{tripId}")
    @Operation(summary = "Inspect one trip and its offer history", description = "Admin only. OFFER_EXPIRED means the stored outstanding offer has reached its deadline; the expiry job or Advance will process it. CONFIRMED bookings and PLANNED trips do not imply driver assignment.")
    public DispatchView get(@PathVariable UUID tripId) { return dispatch.getTrip(tripId); }

    @PostMapping("/trips/{tripId}/advance")
    @Operation(summary = "Advance dispatch or retry after no eligible driver", description = "Admin only; no body. Retains a live offer while its driver and route still fit, retires overdue/ineligible/infeasible offers, or tries the nearest untried eligible driver whose approach and route meet constraints. Maximum three offers per trip by default; attempted drivers never repeat. Cancelled/assigned trips return their current outcome.")
    public DispatchView advance(@PathVariable UUID tripId) { return dispatch.advanceTrip(tripId); }

    @PostMapping("/offers/{offerId}/reject")
    @Operation(summary = "Simulate the offered driver's rejection", description = "Admin only; no body. Records rejection, starts cool-off and automatically offers the trip to the next eligible untried driver. Use activeOfferId from the trip response. Repeating the same rejection is state-idempotent. A response at/after expiry returns 409 OFFER_EXPIRED and commits expiry/next-offer processing.")
    public DispatchView reject(@PathVariable UUID offerId) { return dispatch.rejectOffer(offerId); }

    @PostMapping("/offers/{offerId}/accept")
    @Operation(summary = "Simulate the offered driver's acceptance", description = "Admin only; no body. Uses the driver bound to the stored offer, locks trip/offer/driver, then rechecks expiry, eligibility, actual seats, pickup SLA and detour using current time and current driver approach. Successful assignment persists refreshed stop estimates. A live offer can return 409 ROUTE_INFEASIBLE with a constraint code even before expiry; this leaves assignment and estimates unchanged. Advance can retire it and try another driver. Cancelled trips or conflicting assignments return 409 STATE_CONFLICT; overdue offers return 409 OFFER_EXPIRED and expiry processing commits. Retrying an active accepted assignment returns its state without retimestamping estimates.")
    public DispatchView accept(@PathVariable UUID offerId) {
        return dispatch.acceptOffer(offerId);
    }
}
