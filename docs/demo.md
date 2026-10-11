# Reviewer Demo

Start with the [deterministic demo walkthrough](demo-walkthrough.md): reset and run commands reproduce booking, splits, service-level dispatch and route mutation against the actual HTTP server and PostgreSQL without manual database editing. This page retains the focused test command and optional manual development setup; its single-booking curl examples are not the full reviewer scenario.

## Repeatable invariant demonstration

With JDK 17+ and Docker running, use the wrapper. This selection uses disposable PostgreSQL, seeds its own fixtures and needs no populated `.env`:

```sh
./mvnw '-Dtest=ServiceabilityServiceTest,BookingConcurrencyIntegrationTest,BatchPlannerAcceptanceTest,DetourConstraintTest,DriverEligibilityPolicyTest,DispatchIntegrationTest,TripMutationConcurrencyIntegrationTest,BookingCancellationIntegrationTest,BookingMutationRegressionIntegrationTest,DispatchMutationRegressionIntegrationTest,ConcurrentBatchingIntegrationTest' test
```

On PowerShell substitute `./mvnw.cmd`; keep the quoted `-Dtest` argument. Read assertions/output in this order: serviceable pickup, duplicate race, four-plus-one/SLA split/expired pending, detour rejection, driver eligibility, explicit rejection/re-offer and competing acceptance, late-insertion capacity race, cancellation isolation, stale/concurrent booking mutation, cross-trip driver competition and overlapping batching claims. [Test matrix](test-matrix.md) states the limits of each scenario.

Run `./mvnw clean verify` for the full submission check. The selected demo is not a load test. No recorded video or screenshots are committed.

## Local HTTP walkthrough

Use a disposable local database for the following fixture. It adds one employee and zone; it does not seed employee login accounts or drivers. ADMIN can submit for that employee. There is no zone/employee provisioning API, so explicit SQL is necessary for this HTTP path.

1. Copy `.env.example` to `.env`. Set a local `DB_PASSWORD`, a random `JWT_SECRET` of at least 32 characters, and `LOCAL_ADMIN_ENABLED=true` with your own `LOCAL_ADMIN_USERNAME` / `LOCAL_ADMIN_PASSWORD`. Admin creation only runs in an empty account table.
2. Start PostgreSQL with `docker compose up -d`; wait for healthy status in `docker compose ps`.
3. Export the values for Spring Boot. Compose reads `.env`; Spring does not. In PowerShell:

```powershell
Get-Content .env | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
./mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

In a POSIX shell, export the variables and run `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`. DB_HOST/PORT/NAME/USER and SERVER_PORT can override defaults; operational values are in [application.yml](../src/main/resources/application.yml).

After Flyway completes, use another terminal to open psql (defaults shown; substitute your configured user/database):

```sh
docker compose exec postgres psql -U postgres -d ride_batching
```

```sql
INSERT INTO employees (id, corporate_id, name, email)
VALUES ('00000000-0000-0000-0000-000000000001', 'DEMO-1', 'Demo Employee', 'demo@example.invalid')
ON CONFLICT DO NOTHING;
INSERT INTO serviceable_zones (name, center_latitude, center_longitude, radius_meters, active)
SELECT 'Reviewer Demo', 12.9716, 77.5946, 5000, true
WHERE NOT EXISTS (SELECT 1 FROM serviceable_zones WHERE name = 'Reviewer Demo');
```

Exit with `\q`. Restart the application after direct SQL if its zone cache was populated already; repository eviction is not involved here.

Open [Swagger UI](http://localhost:8080/swagger-ui/index.html) or use `curl` (`curl.exe` on Windows). Login with your supplied local credentials; use the returned token for subsequent calls. Placeholders below must be substituted:

```sh
curl -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' -d '{"username":"<local-admin>","password":"<supplied-password>"}'
curl -X POST http://localhost:8080/api/v1/bookings -H 'Authorization: Bearer <JWT>' -H 'X-Employee-Id: 00000000-0000-0000-0000-000000000001' -H 'Content-Type: application/json' -d '{"pickup":{"latitude":12.9716,"longitude":77.5946},"dropoff":{"latitude":12.9352,"longitude":77.6245}}'
curl -X POST http://localhost:8080/api/admin/batching/trigger -H 'Authorization: Bearer <JWT>'
curl 'http://localhost:8080/api/v1/bookings/<booking-id>' -H 'Authorization: Bearer <JWT>'
curl -X DELETE 'http://localhost:8080/api/v1/bookings/<booking-id>' -H 'Authorization: Bearer <JWT>' -H 'X-Employee-Id: 00000000-0000-0000-0000-000000000001'
```

Before triggering batching, repeat the same create call inside the same UTC slot: both responses should contain one booking ID (201 each). Avoid a slot boundary. On a fresh fixture with scheduling disabled, creation returns PENDING; a prompt batch trigger produces CONFIRMED with tripId if SLA is feasible. Cancellation returns CANCELLED and removes the route's two stops; there is no trip-read HTTP endpoint, so inspect DB or the cancellation integration test for route isolation. Unsupported pickup returns 422; invalid/missing authorization follows the [API contract](api-design.md).

This single-booking walkthrough does not demonstrate automatic dispatch, window grouping or recorded real pickup performance. Those flows are absent. The focused tests demonstrate multi-rider/split and service-level dispatch scenarios.

`docker compose down` preserves the DB volume. Only use `docker compose down -v` when intentionally discarding this local fixture; it deletes local data.
