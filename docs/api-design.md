# Implemented HTTP API

Swagger UI: `/swagger-ui/index.html`. Generated schema: `/v3/api-docs`.

## Authentication and access

`POST /auth/login` is public. Request: `{"username":"<account>","password":"<password>"}`. Response: `{"token":"<JWT>"}`. Blank fields return 400; invalid credentials return 401 with text `Invalid credentials`.

Business requests use `Authorization: Bearer <JWT>`. Booking creation, listing and cancellation also require `X-Employee-Id: <UUID>`. It must match the employee bound to the account unless the caller is an admin. Booking ownership is checked separately on retrieval/cancellation.

Only `EMPLOYEE`, `ADMIN`, `APPROVER` are account roles. See [authorization](auth-boundaries.md).

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

Actuator exposes health, info, metrics and Prometheus without authentication. See [observability](observability.md).

## Sample calls

With an existing account, employee and serviceable pickup:

```sh
curl -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' -d '{"username":"<account>","password":"<password>"}'
curl -X POST http://localhost:8080/api/v1/bookings -H 'Authorization: Bearer <JWT>' -H 'X-Employee-Id: <employee-UUID>' -H 'Content-Type: application/json' -d '{"pickup":{"latitude":12.9716,"longitude":77.5946},"dropoff":{"latitude":12.9352,"longitude":77.6245}}'
curl -X POST http://localhost:8080/api/admin/batching/trigger -H 'Authorization: Bearer <admin-JWT>'
```

## Error contracts and limits

The global handler maps missing booking to 404 (`BOOKING_NOT_FOUND`), unserviceable pickup to 422 (`UNSERVICEABLE_ZONE`) and DTO validation to 400 (`VALIDATION_ERROR`). Responses contain `code`, `message`, `timestamp`, `path`. Invalid/expired JWTs return 401 using that shape. Missing authentication and role/ownership denials return 403 through Spring Security.

Login failure is plain text. Other argument/state errors are not consistently mapped; do not assume 409. See [failure modes](failure-modes.md).

Trip progress/retrieval, driver offers, zone/fleet CRUD and escalation resolution have no HTTP routes.
