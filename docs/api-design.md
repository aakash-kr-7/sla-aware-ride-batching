# API Design Specification

This document defines the HTTP API contracts, role authorization boundaries, and error models for the SLA-aware ride batching system.

---

## 1. Role Authorization Matrix

| Role | Permitted Actions |
| :--- | :--- |
| **`EMPLOYEE`** | Create bookings, view own bookings, cancel own pending/confirmed bookings. |
| **`DRIVER`** | View incoming trip offers, accept/reject offers, update current location and trip execution progress. |
| **`ADMIN`** | Manage serviceable zones, manage drivers/vehicles, inspect all bookings/trips, trigger manual batching cycles. |
| **`APPROVER`** | *Recognized in problem analysis, but no approval workflow is defined by the case study.* No approval gates are implemented in the initial scope. |

> [!NOTE]
> **Approver Role Status**: The case study lists `Approver` as an actor alongside Employee, Driver, and Admin, but specifies no approval rules (such as ride cost thresholds or manager sign-off). Rather than inventing speculative approval mechanics, the system treats `Approver` as a reserved role with no blocking gates in the ride booking lifecycle.

---

## 2. Standard Error Response Format

All error responses share a standard JSON payload:

```json
{
  "code": "UNSERVICEABLE_ZONE",
  "message": "Pickup location [12.8500, 77.5000] is outside all active serviceable zones.",
  "timestamp": "2026-10-10T12:00:15Z",
  "path": "/api/v1/bookings"
}
```

Common status codes:
- `400 Bad Request`: Malformed payload or invalid coordinate bounds.
- `401 Unauthorized`: Missing or invalid authentication token.
- `403 Forbidden`: Insufficient role permissions or accessing another user's resource.
- `404 Not Found`: Resource ID does not exist.
- `409 Conflict`: Invalid state transition (e.g. cancelling a trip already picked up, responding to an expired offer).
- `422 Unprocessable Entity`: Business invariant violation (e.g. unserviceable pickup location).

---

## 3. Endpoints

### 3.1 Authentication

#### `POST /api/v1/auth/login`
- **Role**: Public
- **Description**: Authenticate credentials and receive a JWT token.
- **Request**:
  ```json
  {
    "email": "employee@company.com",
    "password": "secretpassword"
  }
  ```
- **Successful Response (`200 OK`)**:
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsIn...",
    "expiresInSeconds": 3600,
    "role": "EMPLOYEE",
    "userId": "e1a2b3c4-0000-0000-0000-000000000001"
  }
  ```
- **Important Failure Cases**:
  - `400 Bad Request`: Missing email or password.
  - `401 Unauthorized`: Invalid credentials.

---

### 3.2 Bookings

#### `POST /api/v1/bookings`
- **Role**: `EMPLOYEE`
- **Description**: Submit an on-demand ride request. Idempotent: repeated submissions within the same batching slot return the existing booking.
- **Request**:
  ```json
  {
    "pickup": {
      "latitude": 12.9716,
      "longitude": 77.5946
    },
    "dropoff": {
      "latitude": 12.9352,
      "longitude": 77.6245
    }
  }
  ```
- **Successful Response (`201 Created` / `200 OK` on duplicate)**:
  ```json
  {
    "id": "b1a2c3d4-e5f6-7890-abcd-ef1234567890",
    "employeeId": "e1a2b3c4-0000-0000-0000-000000000001",
    "pickup": {
      "latitude": 12.9716,
      "longitude": 77.5946
    },
    "dropoff": {
      "latitude": 12.9352,
      "longitude": 77.6245
    },
    "slotTimestamp": "2026-10-10T12:00:00Z",
    "status": "PENDING",
    "pickupSlaDeadline": "2026-10-10T12:21:15Z",
    "tripId": null,
    "createdAt": "2026-10-10T12:01:15Z"
  }
  ```
- **Important Failure Cases**:
  - `400 Bad Request`: Coordinates missing or out of valid ranges.
  - `422 Unprocessable Entity` (`UNSERVICEABLE_ZONE`): Pickup coordinates do not fall inside any active serviceable zone.

#### `GET /api/v1/bookings/{id}`
- **Role**: `EMPLOYEE` (owner) or `ADMIN`
- **Description**: Retrieve booking details and tracking status.
- **Request**: None.
- **Successful Response (`200 OK`)**:
  ```json
  {
    "id": "b1a2c3d4-e5f6-7890-abcd-ef1234567890",
    "employeeId": "e1a2b3c4-0000-0000-0000-000000000001",
    "pickup": { "latitude": 12.9716, "longitude": 77.5946 },
    "dropoff": { "latitude": 12.9352, "longitude": 77.6245 },
    "slotTimestamp": "2026-10-10T12:00:00Z",
    "status": "CONFIRMED",
    "pickupSlaDeadline": "2026-10-10T12:21:15Z",
    "tripId": "t9z8y7x6-w5v4-3210-fedc-ba0987654321",
    "estimatedPickupTime": "2026-10-10T12:12:00Z",
    "createdAt": "2026-10-10T12:01:15Z"
  }
  ```
- **Important Failure Cases**:
  - `403 Forbidden`: Requesting employee does not own this booking.
  - `404 Not Found`: Booking ID does not exist.

#### `POST /api/v1/bookings/{id}/cancel`
- **Role**: `EMPLOYEE` (owner) or `ADMIN`
- **Description**: Cancel a booking. If `CONFIRMED`, triggers localized replanning of the assigned trip without affecting unrelated trips.
- **Request**:
  ```json
  {
    "reason": "Change of schedule"
  }
  ```
- **Successful Response (`200 OK`)**:
  ```json
  {
    "id": "b1a2c3d4-e5f6-7890-abcd-ef1234567890",
    "status": "CANCELLED",
    "updatedAt": "2026-10-10T12:06:30Z"
  }
  ```
- **Important Failure Cases**:
  - `403 Forbidden`: Requesting user is not the owner or an admin.
  - `404 Not Found`: Booking ID does not exist.
  - `409 Conflict` (`INVALID_STATE_TRANSITION`): Booking is in `PICKED_UP` or `DROPPED` state and cannot be cancelled.

---

### 3.3 Trips

#### `GET /api/v1/trips/{id}`
- **Role**: `EMPLOYEE` (if passenger), `DRIVER` (if assigned), or `ADMIN`
- **Description**: Retrieve trip details including sequenced stops, assigned vehicle/driver, and current trip status.
- **Request**: None.
- **Successful Response (`200 OK`)**:
  ```json
  {
    "id": "t9z8y7x6-w5v4-3210-fedc-ba0987654321",
    "status": "DISPATCHED",
    "vehicleId": "v1000000-0000-0000-0000-000000000001",
    "driverId": "d1000000-0000-0000-0000-000000000001",
    "windowStartTime": "2026-10-10T12:00:00Z",
    "stops": [
      {
        "id": "s1-...",
        "bookingId": "b1a2c3d4-...",
        "type": "PICKUP",
        "sequenceOrder": 1,
        "location": { "latitude": 12.9716, "longitude": 77.5946 },
        "estimatedArrivalTime": "2026-10-10T12:12:00Z",
        "status": "PENDING"
      },
      {
        "id": "s2-...",
        "bookingId": "b2c3d4e5-...",
        "type": "PICKUP",
        "sequenceOrder": 2,
        "location": { "latitude": 12.9730, "longitude": 77.5960 },
        "estimatedArrivalTime": "2026-10-10T12:15:30Z",
        "status": "PENDING"
      },
      {
        "id": "s3-...",
        "bookingId": "b1a2c3d4-...",
        "type": "DROPOFF",
        "sequenceOrder": 3,
        "location": { "latitude": 12.9352, "longitude": 77.6245 },
        "estimatedArrivalTime": "2026-10-10T12:28:00Z",
        "status": "PENDING"
      },
      {
        "id": "s4-...",
        "bookingId": "b2c3d4e5-...",
        "type": "DROPOFF",
        "sequenceOrder": 4,
        "location": { "latitude": 12.9340, "longitude": 77.6260 },
        "estimatedArrivalTime": "2026-10-10T12:31:00Z",
        "status": "PENDING"
      }
    ],
    "createdAt": "2026-10-10T12:15:05Z"
  }
  ```
- **Important Failure Cases**:
  - `403 Forbidden`: User is neither a passenger, assigned driver, nor admin.
  - `404 Not Found`: Trip ID does not exist.

---

### 3.4 Driver Offers

#### `POST /api/v1/driver/offers/{offerId}/respond`
- **Role**: `DRIVER`
- **Description**: Accept or reject a dispatched trip offer.
- **Request**:
  ```json
  {
    "action": "REJECT"
  }
  ```
  *(Allowed values for `action`: `ACCEPT`, `REJECT`)*
- **Successful Response (`200 OK`)**:
  ```json
  {
    "offerId": "off1-0000-0000-0000-000000000001",
    "tripId": "t9z8y7x6-w5v4-3210-fedc-ba0987654321",
    "status": "REJECTED",
    "respondedAt": "2026-10-10T12:16:30Z"
  }
  ```
- **Important Failure Cases**:
  - `400 Bad Request`: Invalid action string.
  - `403 Forbidden`: Driver is not the recipient of this offer.
  - `404 Not Found`: Offer ID does not exist.
  - `409 Conflict` (`OFFER_ALREADY_RESOLVED`): Offer was already responded to or has expired (`EXPIRED`).

---

### 3.5 Administrative Endpoints

#### `POST /api/v1/admin/batching/run`
- **Role**: `ADMIN`
- **Description**: Trigger an immediate batching cycle for testing or demonstration without waiting for the scheduled 15-minute window timer.
- **Request**:
  ```json
  {
    "windowStartTime": "2026-10-10T12:00:00Z"
  }
  ```
  *(Optional: If omitted, processes the most recent unbatched window).*
- **Successful Response (`200 OK`)**:
  ```json
  {
    "windowStartTime": "2026-10-10T12:00:00Z",
    "processedBookings": 6,
    "tripsCreated": 2,
    "unbatchedBookings": 0,
    "createdTripIds": [
      "t9z8y7x6-w5v4-3210-fedc-ba0987654321",
      "t8y7x6w5-v4u3-210f-edcb-a98765432109"
    ]
  }
  ```
- **Important Failure Cases**:
  - `403 Forbidden`: Caller lacks `ADMIN` role.

#### `POST /api/v1/admin/zones`
- **Role**: `ADMIN`
- **Description**: Create a serviceable zone boundary.
- **Request**:
  ```json
  {
    "name": "Outer Ring Road Tech Zone",
    "center": {
      "latitude": 12.9250,
      "longitude": 77.6850
    },
    "radiusMeters": 4000.0,
    "active": true
  }
  ```
- **Successful Response (`201 Created`)**:
  ```json
  {
    "id": "z1-0000-0000-0000-000000000001",
    "name": "Outer Ring Road Tech Zone",
    "center": { "latitude": 12.9250, "longitude": 77.6850 },
    "radiusMeters": 4000.0,
    "active": true
  }
  ```
- **Important Failure Cases**:
  - `400 Bad Request`: Invalid coordinates or non-positive radius.

#### `GET /api/v1/admin/zones`
- **Role**: `ADMIN` (and accessible by `EMPLOYEE` to check zone boundaries)
- **Description**: List all serviceable zones.
- **Request**: None.
- **Successful Response (`200 OK`)**:
  ```json
  [
    {
      "id": "z1-0000-0000-0000-000000000001",
      "name": "Outer Ring Road Tech Zone",
      "center": { "latitude": 12.9250, "longitude": 77.6850 },
      "radiusMeters": 4000.0,
      "active": true
    }
  ]
  ```

#### `POST /api/v1/admin/drivers`
- **Role**: `ADMIN`
- **Description**: Register a driver and associate them with a vehicle.
- **Request**:
  ```json
  {
    "name": "Vikram Singh",
    "vehicleId": "v1000000-0000-0000-0000-000000000001",
    "initialLocation": {
      "latitude": 12.9260,
      "longitude": 77.6840
    }
  }
  ```
- **Successful Response (`201 Created`)**:
  ```json
  {
    "id": "d1000000-0000-0000-0000-000000000001",
    "name": "Vikram Singh",
    "vehicleId": "v1000000-0000-0000-0000-000000000001",
    "status": "AVAILABLE",
    "currentLocation": { "latitude": 12.9260, "longitude": 77.6840 }
  }
  ```
- **Important Failure Cases**:
  - `400 Bad Request`: Missing driver name or invalid initial location.
  - `404 Not Found`: Vehicle ID not found.
