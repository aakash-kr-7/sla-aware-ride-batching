# Inspect trip results through the API

Use the existing authenticated admin trip endpoints. No new account role, dashboard or decision-history store is required. Startup/provisioning is described in [local development](local-development.md); these requests inspect existing bookings and trips.

## In Swagger

1. Log in through **Authentication**, copy the returned token and paste it into **Authorize** without a `Bearer` prefix.
2. After **Admin (Batching) > Plan trips and offer eligible drivers**, open **Admin (Dispatch simulation) > List trips, routes and dispatch outcomes**. Execute and find the returned trip IDs. The list contains the same inspection fields as a single trip.
3. Open **Inspect one trip, grouped bookings and pickup promises**. Copy a `tripId` from the list; leave `candidateBookingId` empty and Execute.
4. To examine sharing constraints, use a **PLANNED** trip and supply the booking ID of a pending request or a confirmed booking on another trip. Execute again and read `insertionCheck`. This does not move that booking or advance dispatch.
5. To distinguish planning from assignment, accept the active offer and inspect the trip again **without** a candidate ID. Acceptance sets the driver association and refreshes route estimates with approach time. Comparing a candidate against an already assigned trip is unsupported.

GET reads state. The booking, trip, employee and offer IDs identify different records; use a booking ID for the optional candidate, not an employee or offer ID.

## Reading the response

| Field | Meaning |
| --- | --- |
| `tripStatus`, `assignedDriverId` | PLANNED is a route with no accepted driver; DISPATCHED is accepted assignment. IN_PROGRESS/COMPLETED reflect admin-simulated execution. A cancelled trip can retain its historical driver ID. |
| `bookingIds` | Distinct bookings currently present on the route, ordered by their first stop. Cancelled riders removed from the route are absent, even though their booking retains its historical trip ID. |
| `stops` | Ordered pickup/dropoff pairs with sequence, booking ID and coordinates. For each booking, pickup precedes dropoff. |
| `planningCapacity`, `peakPassengers` | Current configured planning seats versus simultaneous route occupancy. Planning capacity is not the offered driver's seats and is not a persisted historical configuration snapshot. Four seats can serve more than four bookings when riders turn over. |
| `stops[].estimatedArrivalAt` | Persisted estimate from planning, acceptance or the last route change. GET does not recalculate it. Legacy estimates may be null. This is not measured arrival. |
| `stops[].pickupSlaDeadline` | The booking's stored promise, present only on pickup rows. |
| `stops[].withinPickupSla` | Whether that saved pickup estimate is at/before the promise. Equality passes. Null for dropoffs or missing estimates; null does not mean SLA passed. |
| `outcome`, `activeOfferId`, `offers` | Existing offer/assignment state and persisted offer history. A live offer is not a driver reservation or a current SLA guarantee. |
| `insertionCheck` | Null on ordinary inspection, list and mutation responses. Populated only for the optional single-trip comparison. |

Planning/pending-offer estimates are optimistic at the first pickup. New acceptance uses current time and stored driver position, repeats route constraints and persists refreshed estimates; [ETA policy](planner-and-eta.md) defines these boundaries. A saved `withinPickupSla=true` does not mean an offer can still be accepted later.

## Explaining rejected sharing

The optional comparison reuses `RouteInsertionPlanner` and `TripFeasibilityEvaluator`. It searches alternatives for **one** existing trip and returns:

- `candidateBookingId`, `evaluatedAt`, `routeStartTime` and `timingBasis=UNASSIGNED_CURRENT_TIME`. First pickup is at max(current dispatch time, original planning start), without approach from an unassigned driver.
- `feasible` and the cheapest feasible `proposedStops`, including fresh estimates/deadlines. An empty proposed route means no insertion passed.
- `alternativesEvaluated`: how many alternatives reached constraint checks. After a feasible cost is found, equally/more expensive alternatives cannot improve it and can skip those checks.
- `rejections`: counts grouped by first-failure constraint, with one representative example per code. For SLA, the example is the closest missed promise among evaluated failures; other codes retain the first encountered example. Capacity examples expose peak occupancy; SLA examples expose affected booking, estimated pickup and deadline; detour examples expose direct/shared seconds. Null fields are inapplicable to that reason.

**Rejected alternatives do not necessarily reject the booking.** If `feasible=true`, another insertion succeeded despite those rejected alternatives. If false, every legal insertion failed. Constraints short-circuit, so these are first failures, not an exhaustive list of every violation in each route.

This is a **fresh hypothetical route check**, not a reconstruction of the original batch decision. It does not apply new-batch source-window, zone or direction grouping, reserve seats, choose a driver or alter assignment. A feasible comparison of a confirmed booking on another trip does not move it. Earlier planning traces remain in the existing debug logs; no historical decision guarantee is added. Advancing time or changing data can change the result.

Only planned trips and pending/confirmed candidates outside their route are supported. Already-grouped, cancelled or executed candidates return 400; missing bookings return 404; assigned/executing/terminal trips return 409 `STATE_CONFLICT`. Admin authorization still applies to both ordinary and optional inspection.

The search streams alternatives and retains a bounded set of representative reason codes; it does not return every candidate route. It retains the existing O(S³) per-trip cost and O(S) temporary route space. Long routes are not capped. Inspection/readiness checks do not increment operational rejection counters or emit planning decision logs; planning, offering and new acceptance retain their normal instrumentation.

## Commands and evidence

PowerShell; set values from real login/list/booking responses. Use the running server's port (normal application default: 8080).

```powershell
$baseUrl = 'http://127.0.0.1:8080'
# $adminToken: login token; $tripId: trip UUID; $candidateBookingId: booking UUID.
curl.exe "$baseUrl/api/admin/dispatch/trips?page=0&size=20" -H "Authorization: Bearer $adminToken"
curl.exe "$baseUrl/api/admin/dispatch/trips/$tripId" -H "Authorization: Bearer $adminToken"
curl.exe "$baseUrl/api/admin/dispatch/trips/${tripId}?candidateBookingId=$candidateBookingId" -H "Authorization: Bearer $adminToken"
```

Run the real HTTP/PostgreSQL evidence with Java 17+ and Docker:

```powershell
.\mvnw.cmd '-Dtest=TripInspectionHttpIntegrationTest' test
```

On Linux/macOS use `./mvnw`. The [tests](../src/test/java/com/ridebatching/dispatch/api/TripInspectionHttpIntegrationTest.java) invoke actual batching and authenticated inspection, prove four-plus-one grouping, pickup/deadline projection, accepted-driver approach, cancellation isolation, legacy unknown estimates, role denial, capacity/SLA rejection examples, a costlier feasible alternative, current-time semantics and unchanged routes/associations/counters. Test reference data is created in disposable PostgreSQL; no manual SQL/provisioning is needed to run them.

Implementation: [trip response](../src/main/java/com/ridebatching/dispatch/DispatchView.java), [authenticated controller](../src/main/java/com/ridebatching/dispatch/api/DispatchAdminController.java), [comparison](../src/main/java/com/ridebatching/dispatch/TripInsertionInspector.java) and [shared evaluator](../src/main/java/com/ridebatching/batching/evaluator/TripFeasibilityEvaluator.java).
