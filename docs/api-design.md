# Implemented HTTP API

Swagger UI: `/swagger-ui/index.html`. Generated schema: `/v3/api-docs`.

The opening description explains the sequence: login, Authorize with the returned token, list/create a booking, switch to an admin token for batching, then retrieve the booking to inspect status/trip association. Endpoint summaries and parameter descriptions identify required IDs and which calls change data. Example coordinates are illustrative and require configured business records; startup seeds none.

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

Batching accepts no window parameter and considers all pending bookings. Escalations must currently be supplied separately; batching does not create them.

Actuator exposes health, info, metrics and Prometheus without authentication. See [operational endpoints](failure-modes.md#health-metrics-and-logs).

## Error contracts and limits

The global handler maps missing booking to 404 (`BOOKING_NOT_FOUND`), unserviceable pickup to 422 (`UNSERVICEABLE_ZONE`) and DTO validation to 400 (`VALIDATION_ERROR`). Responses contain `code`, `message`, `timestamp`, `path`. Invalid/expired JWTs return 401 using that shape. Missing authentication returns 403 through Spring Security. Role/ownership denials return 403; controller advice emits `FORBIDDEN` with a generic message.

Login failure is plain text. Invalid arguments return 400 (`INVALID_REQUEST`); explicit domain transitions and optimistic/pessimistic lock conflicts return 409 (`STATE_CONFLICT`). Conflict responses do not echo database details. Unexpected internal failures remain server errors. The JWT filter handles only token/account authentication errors; it does not relabel downstream failures. See [failure modes](failure-modes.md).

Trip progress/retrieval, driver offers, zone/fleet CRUD and escalation resolution have no HTTP routes.
