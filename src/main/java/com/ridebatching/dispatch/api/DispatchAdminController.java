package com.ridebatching.dispatch.api;

import com.ridebatching.dispatch.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
    @Operation(summary = "List trips, routes and dispatch outcomes", description = "Admin only. Newest trips first; page is zero-based and size must be 1..100 (default 20). Inspect bookingIds, ordered stops, peakPassengers beside planningCapacity, driver association and offer history. Pickup stops show stored estimatedArrivalAt, pickupSlaDeadline and withinPickupSla; dropoffs and unknown legacy estimates have null SLA results. Planning capacity is current configuration, not the assigned driver's seats or a historical snapshot. Reading never advances dispatch or retimestamps routes.")
    public Page<DispatchView> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return dispatch.listTrips(page, size);
    }

    @GetMapping("/trips/{tripId}")
    @Operation(summary = "Inspect one trip, grouped bookings and pickup promises", description = "Admin only. Compare bookingIds and ordered stops to see sharing; peakPassengers is simultaneous occupancy and planningCapacity is the current configured planning limit. Pickup rows expose saved estimates beside stored deadlines, not actual arrivals or a live ETA recalculation. Acceptance refreshes estimates with driver approach; pending-offer estimates remain optimistic. Optionally supply candidateBookingId for a planned trip: insertionCheck tests all promising insertion alternatives at current time and returns feasibility, a proposed route and representative first-failure reasons. This is a fresh hypothetical route-constraint check, not historical decision history; it ignores new-batch window/zone/direction grouping and does not assign the candidate. A confirmed booking from another trip may be compared. Already grouped/cancelled/executed candidates return 400; missing candidates return 404; comparison on an assigned/executing/terminal trip returns 409 STATE_CONFLICT. OFFER_EXPIRED means the outstanding offer has reached its deadline; the expiry job or Advance processes it. CONFIRMED bookings and PLANNED trips do not imply driver assignment.")
    public DispatchView get(@PathVariable UUID tripId,
                            @Parameter(description = "Optional booking outside this planned route: compute a fresh hypothetical insertion check with rejection examples, without assigning it. Pending or confirmed bookings only; no historical decision claim or batch grouping check.")
                            @RequestParam(required = false) UUID candidateBookingId) {
        return dispatch.getTrip(tripId, candidateBookingId);
    }

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
