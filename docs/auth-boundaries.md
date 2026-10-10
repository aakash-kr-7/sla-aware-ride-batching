# Authorization Boundaries

`UserAccount` owns username, BCrypt hash and role. Its optional employee UUID links identity to transport-domain `Employee`; employees store no credentials.

`POST /auth/login` issues a signed, expiring JWT after authentication. The filter validates it and loads current account details by username. Method authorization uses that account's current role/employee ID.

| Route | Access/ownership |
| --- | --- |
| `POST /auth/login` | Public |
| `POST /api/v1/bookings` | Header employee ID equals principal employee ID, or ADMIN |
| `GET /api/v1/bookings` | Header employee ID equals principal employee ID, or ADMIN |
| `GET /api/v1/bookings/{id}` | Returned booking belongs to principal employee, or ADMIN |
| `DELETE /api/v1/bookings/{id}` | Header identity check; service also requires booking ownership by supplied employee |
| `POST /api/admin/batching/trigger` | ADMIN |
| `GET /api/v1/escalations/open` | APPROVER or ADMIN |
| Actuator, Swagger UI, OpenAPI | Public |

Roles are `EMPLOYEE`, `ADMIN`, `APPROVER`; there is no driver role. Approvers inspect records; resolution workflows are absent.

Runtime `JWT_SECRET` is mandatory. Tests supply separate test-only material. Default runtime creates no accounts. Admin bootstrap requires the `local` profile, explicit enablement, environment credentials and an empty account table.

Missing authentication returns 403; invalid/expired token returns 401. No revocation list exists. The JWT filter also catches downstream exceptions and can misclassify domain faults as 401; see [failure limits](failure-modes.md). Operational endpoints remain public under the existing policy.
