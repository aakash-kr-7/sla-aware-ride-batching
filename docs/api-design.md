# Implemented HTTP API

Swagger UI: `/swagger-ui/index.html`. Generated schema: `/v3/api-docs`.

The opening description explains the sequence: login, Authorize with the returned token, list/create a booking, switch to an admin token for batching, then retrieve the booking to inspect status/trip association. Admin dispatch simulation lists routes and offer history, rejects the stored driver's offer to see the next driver, and accepts to commit assignment. Admin execution simulation then advances ordered pickup/dropoff stops through trip completion. Endpoint summaries and parameter descriptions identify required IDs and which calls change data. Example coordinates are illustrative and require configured business records; startup seeds none.

## Authentication and access

`POST /auth/login` is public. Request: `{"username":"<account>","password":"<password>"}`. Response: `{"token":"<JWT>"}`. Blank fields return 400; invalid credentials return 401 with text `Invalid credentials`.

Business requests use `Authorization: Bearer <JWT>`. Booking creation, listing and cancellation also require `X-Employee-Id: <UUID>`. It must match the employee bound to the account unless the caller is an admin. Booking ownership is checked separately on retrieval/cancellation.

`UserAccount` stores a BCrypt password hash and an optional employee link. JWTs expire after one hour by default. The filter verifies the token and reloads current account role/employee data by username; claims alone are not the method-authorization source. There is no revocation list.

Roles are `EMPLOYEE`, `ADMIN`, `APPROVER`; no driver role exists. Booking methods permit ADMIN or matching employee identity, not an explicit EMPLOYEE-only role check. Any account role with the matching employee link can satisfy that condition. Cancellation also requires the supplied employee to own the booking, including for admins.

`JWT_SECRET` is required. Default runtime creates no accounts. Local admin bootstrap requires the local profile, explicit enablement, supplied credentials and an empty account table.

## Booking routes

| Method | Route | Input | Response |
| --- | --- | --- | --- |
| POST | `/api/v1/bookings` | Employee header and pickup/dropoff JSON | 201 and booking, including duplicate submissions |
| GET | `/api/v1/bookings/{id}` | Booking UUID | 200 and booking; owner or admin |
| GET | `/api/v1/bookings` | Employee header; optional `page`, `size`, `sort` | 200 and paginated bookings |
| DELETE | `/api/v1/bookings/{id}` | Employee header and booking UUID; no body | 200 and cancelled booking |

Create request:

```json
{
  "pickup": {"latitude": 12.9716, "longitude": 77.5946},
  "dropoff": {"latitude": 12.9352, "longitude": 77.6245}
}
```

`BookingResponse` contains `id`, `employeeId`, `pickup`, `dropoff`, `slotTimestamp`, `status`, `pickupSlaDeadline`, `tripId`, `createdAt`. Times are ISO-8601 instants. `tripId` is null while unassigned; creation may immediately confirm a booking via late insertion. There is no estimated pickup or updated timestamp response field.

The page contains `content`, `totalElements`, `totalPages`, `size`, `number` (zero-based).

## Operational routes

| Method | Route | Role | Input/result |
| --- | --- | --- | --- |
| POST | `/api/admin/batching/trigger` | ADMIN | No body; returns `bookingsConsidered`, `tripsFormed`, `bookingsLeftPending` |
| GET | `/api/v1/escalations/open` | ADMIN or APPROVER | Open entities: `id`, `referenceId`, `referenceType`, `reason`, `status`, `createdAt`, `updatedAt` |

Batching accepts no window/force parameter. It claims at most the configured limit (default 100) of closed-window or urgent unexpired pending bookings, then groups by source window, zone and direction. Open-window bookings with more than the configured urgent budget remain collecting, including on manual triggers. Expired bookings remain pending but are excluded from claims. Counts describe this cycle's claims, not the total queue; zero considered can coexist with saved pending bookings. See [exact rules and settings](batching-strategy.md#collection-and-eligibility-rules). A separate bounded pass persists one OPEN issue per expired pending booking/reason; cancellation resolves its automatic issues. Eligible-claim counts exclude that recovery pass. See [lifecycle and failure policy](booking-lifecycle.md).

Each newly formed trip attempts its initial offer in the batching transaction. An unavailable driver does not undo a feasible route; dispatch inspection shows the outcome.

Actuator exposes health, info, metrics and Prometheus without authentication. See [operational endpoints](failure-modes.md#health-metrics-and-logs).

## Admin dispatch simulation

All five operations require ADMIN. No employee header, driver ID or request body is accepted; responses refer to the driver bound to the stored offer.

| Method | Route | Result |
| --- | --- | --- |
| GET | `/api/admin/dispatch/trips?page=0&size=20` | Newest trip views; size 1–100 |
| GET | `/api/admin/dispatch/trips/{tripId}` | Route, peak passengers, outcome, activeOfferId and offer history |
| POST | `/api/admin/dispatch/trips/{tripId}/advance` | Reuse live offer or expire/attempt next; no attempt reset |
| POST | `/api/admin/dispatch/offers/{offerId}/reject` | Reject and automatically consider next driver |
| POST | `/api/admin/dispatch/offers/{offerId}/accept` | Commit assignment after locked rechecks; return trip view |

`DispatchView` contains `tripId`, `tripStatus`, `assignedDriverId`, `peakPassengers`, `outcome`, `activeOfferId`, `attemptsUsed`, `attemptLimit`, `offers`, `stops`, `planningCapacity`, `bookingIds` and optional `insertionCheck`. Offer items include ID, stored driver ID, status and created/expiry/updated timestamps; stops include sequence, booking ID, type, location and `estimatedArrivalAt` (nullable for legacy routes); pickup rows also expose `pickupSlaDeadline` and nullable `withinPickupSla`. Planning capacity is the current configured limit, not a historical snapshot or assigned vehicle capacity. These views avoid serializing managed entities. Peak passengers is occupancy, not vehicle capacity. New acceptance recalculates arrivals using current driver approach and repeats route constraints; planning/pending-offer estimates are optimistic. Outcomes and [ETA/revalidation policy](planner-and-eta.md) define the distinction.

Single-trip GET accepts optional `candidateBookingId` on a PLANNED trip. It returns a fresh hypothetical insertion check with first-failure rejection counts/examples and the cheapest feasible proposed route; it neither reconstructs history nor assigns the candidate. Pending/confirmed candidates outside the route may be compared, including a confirmed booking on another trip. Ordinary/list/mutation responses leave `insertionCheck` null. [Swagger steps, field semantics, errors and HTTP evidence](trip-inspection.md).

## Admin execution simulation

| Method | Route | Result |
| --- | --- | --- |
| POST | `/api/admin/bookings/{bookingId}/pickup` | Booking PICKED_UP; first pickup starts trip IN_PROGRESS |
| POST | `/api/admin/bookings/{bookingId}/dropoff` | Booking DROPPED; final dropoff completes trip and releases driver |

Both require ADMIN, take no body and use the accepted driver already stored on the trip. Execute only the next unfinished stop from the dispatch view. CONFIRMED means feasible route association, not driver acceptance. Invalid order, missing acceptance, repeats or terminal states return 409 STATE_CONFLICT. Cancellation is supported only until the first pickup anywhere in the trip; it recomputes remaining arrival estimates. See [exact rules and evidence](booking-lifecycle.md).

## Error contracts and limits

The global handler maps missing booking to 404 (`BOOKING_NOT_FOUND`), unserviceable pickup to 422 (`UNSERVICEABLE_ZONE`) and DTO validation to 400 (`VALIDATION_ERROR`). Responses contain `code`, `message`, `timestamp`, `path`. Invalid/expired JWTs return 401 using that shape. Missing authentication returns 403 through Spring Security. Role/ownership denials return 403; controller advice emits `FORBIDDEN` with a generic message.

Missing trip/offer returns 404 `DISPATCH_NOT_FOUND`. Acceptance/rejection at offer expiry returns 409 `OFFER_EXPIRED`; the expiry transition and next offer intentionally commit despite that response. Cancelled-trip or unavailable-driver acceptance returns 409 `STATE_CONFLICT`.

A stored route that fails new assignment or cancellation capacity/detour checks returns 409 ROUTE_INFEASIBLE with a controlled constraint code in the message, e.g. PICKUP_SLA or RELATIVE_DETOUR. Failed new acceptance retains the offer without assigning or changing estimates; Advance can retire it and consider another feasible driver.

Login failure is plain text. Invalid arguments return 400 (`INVALID_REQUEST`); explicit domain transitions and optimistic/pessimistic lock conflicts return 409 (`STATE_CONFLICT`). Conflict responses do not echo database details. Unexpected internal failures remain server errors. The JWT filter handles only token/account authentication errors; it does not relabel downstream failures. See [failure modes](failure-modes.md).

Zone/fleet CRUD and manual escalation resolution have no HTTP routes. Admin-only dispatch inspection and simulated offer responses are available; see [dispatch workflow](dispatch-workflow.md).
