# Run locally

There are two startup paths. **Use the disposable fixture setup to review the project.** Use normal configuration when supplying your own database and reference data. Neither requires a system Maven installation.

## A fresh clone with usable accounts

Install JDK 17 or later and Docker with Compose; start Docker. Ports **8080** (application) and **55433** (local PostgreSQL) must be free.

```sh
git clone https://github.com/aakash-kr-7/sla-aware-ride-batching.git
cd sla-aware-ride-batching
docker compose -f compose.local.yml up -d --wait
```

Start the application in that directory. Keep this terminal open:

| Terminal | Command |
| --- | --- |
| Windows PowerShell | `.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local-fixtures'` |
| Linux/macOS | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local-fixtures` |

Wait for `Local fixtures loaded` (or `Local reference data already exists`). First startup downloads Maven dependencies and runs all Flyway migrations. No `.env`, private files or SQL editing is needed for this path.

Open the [request console](http://127.0.0.1:8080/) or [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html) for the complete API. Both call the authenticated backend and save real bookings and trips.

## Using the request console

Select **Use local credentials**, then **Connect**. The page checks service readiness and authenticates the admin and nine employees. Tokens stay in tab memory; opening the page creates no bookings. Swagger requires a separate login and authorization.

**Guided example** uses five requests to exercise deduplication, outside-zone rejection, a four-plus-one split, driver replacement, cancellation and metrics. Each completed action unlocks the next stage. Start with a fresh disposable database and finish within about ten minutes: pickup deadlines and driver approach checks still apply.

**Your scenario** accepts one to nine journeys. Select a different employee for each row and enter pickup/dropoff coordinates. **Send requests** submits them concurrently and displays each response or error. **Run batching** processes eligible pending requests and refreshes the saved trips. The nine default long journeys normally form **4 + 4 + 1** on a fresh database; other inputs can produce different results.

Each trip displays its riders, peak occupancy, pickup deadlines and assignment state. Expand the route or offer history for details.

- **Accept offer** and **Reject offer** act on an active driver offer. An offer does not reserve a driver. For the default nine-rider scenario, assign the two four-rider groups before the solo rider so the larger cabs serve the groups that need them.
- **Find next driver** advances a planned trip to an untried eligible driver while attempts remain. Use it when another trip has taken the offered driver.
- **Record next stop** submits the next unfinished pickup or dropoff in route order. The final dropoff completes the trip and releases the driver.
- **Cancel a rider** removes a booking before execution and updates only its associated route. Cancellation closes for the entire trip at the first pickup.

Driver responses and stop events are admin simulations; they do not measure physical cab movement. The backend rechecks availability and route constraints before accepting an assignment.

The request log shows payloads, responses, status codes and duration. **Download JSON** exports up to 100 retained calls with login passwords and tokens redacted. Requests are not retried automatically; after a timeout, refresh and inspect state before submitting again. **Refresh** fetches current state, and **Sign out** clears tab state without resetting the database.

## What the fixtures contain

All accounts below use **`local-only-password`**. These are disposable local credentials.

| Identity | Role / employee ID |
| --- | --- |
| `local-admin` | ADMIN; no employee association |
| `local-rider-1` through `local-rider-9` | EMPLOYEE; `00000000-0000-0000-0000-000000000001` through `00000000-0000-0000-0000-000000000009` |

The loader creates nine employees, ten BCrypt-backed accounts, two active pickup zones and four drivers. **It creates no bookings or trips.** Restarting retains existing rides and driver state; it does not reset them.

| Reference | Configuration |
| --- | --- |
| Central pickup zone | Centre `(12.9716, 77.5946)`, radius 5 km |
| Northern pickup zone | Centre `(13.0716, 77.5946)`, radius 3 km |
| Driver `00000000-0000-0000-0000-000000000065` | Available, four seats, at the central pickup; decimal ID 101 |
| Driver `00000000-0000-0000-0000-000000000066` | Available, four seats, at `(12.9800, 77.5946)`; backup |
| Driver `00000000-0000-0000-0000-000000000067` | Available, two seats; cannot serve the four-rider route |
| Driver `00000000-0000-0000-0000-000000000068` | Offline, four seats; ineligible |

The default journeys use the central pickup and destination `(12.8000, 77.5946)`. Different employees make the requests logically distinct. The long common journey prevents seat turnover before the remaining pickup promises, so four seats split five requests into four plus one. Shorter journeys with generous deadlines can legitimately serve five bookings in one trip.

### Isolation and policy differences

[compose.local.yml](../compose.local.yml) owns project `ride-batching-local`, database `ride_batching_local`, volume `ride-batching-local_local_postgres_data` and loopback port 55433. It is independent of the normal Compose file and `.env`. The application binds loopback port 8080. The public database password and JWT signing key belong only to this explicit disposable profile.

| Policy | Normal runtime | `local-fixtures` |
| --- | --- | --- |
| Business fixtures | None | Explicitly load the reference data above |
| Urgent lead time | 5 minutes | 20 minutes: fresh requests become eligible immediately |
| Offer lifetime | 2 minutes | 10 minutes: time to inspect before responding |
| Collection window / pickup SLA | 15 / 20 minutes | Unchanged |
| Seats, detour, driver approach, acceptance checks | Enforced | Unchanged |
| Batch scheduler / offer-expiry scheduler | Off / on | Unchanged |

The fixture loader checks the connected database name, serializes concurrent loads in a transaction, refuses foreign or incomplete business data, and rolls back a failed load. A known complete fixture set is retained without overwriting mutations. This is a local setup guard, not a general data-migration tool. [Implementation](../src/main/java/com/ridebatching/dev/LocalFixtures.java), [tests](../src/test/java/com/ridebatching/dev/LocalFixturesIntegrationTest.java).

## Stop or reset the disposable setup

Stop the application with **Ctrl+C first**. Retain data:

```sh
docker compose -f compose.local.yml stop
```

To repeat from scratch, remove **this local project's** database and start it again:

```sh
docker compose -f compose.local.yml down --volumes
docker compose -f compose.local.yml up -d --wait
```

Then restart the application with `local-fixtures`. An older five-employee fixture database also needs this reset after upgrading; the loader refuses to overwrite existing rides or silently repair an incomplete fixture set. Removing the volume deletes these disposable rides and accounts. It does not target the normal Compose project or any private setup.

## Normal configuration, without fixtures

Copy `.env.example` to `.env` if you do not already have one. Supply `DB_PASSWORD` and a `JWT_SECRET` of at least 32 bytes. Compose reads `.env`; Spring Boot needs those values exported into its process. Do not commit populated `.env` files.

PowerShell:

```powershell
Copy-Item .env.example .env
# Edit .env before continuing. It uses simple, unquoted NAME=value lines.
docker compose up -d --wait
Get-Content .env | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
.\mvnw.cmd spring-boot:run
```

Linux/macOS, after editing `.env`:

```sh
docker compose up -d --wait
set -a
. ./.env
set +a
./mvnw spring-boot:run
```

Normal startup creates no employees, accounts, zones or drivers. There is no reference-data CRUD/registration API. The separate `local` profile can create only an admin, on an empty account table, when `LOCAL_ADMIN_ENABLED=true` and username/password are supplied; start with `-Dspring-boot.run.profiles=local`. The fixture setup above is the easiest public path to usable booking data.

## Common failures

| Symptom | Action |
| --- | --- |
| Docker unavailable | Start Docker and verify `docker info`; Compose and PostgreSQL integration tests need it. |
| Port already used | Stop the process using 8080 or the local database using 55433. Changing ports also requires matching application configuration. |
| Login fails | Confirm `local-fixtures` was selected and the fixture-loaded log appeared; use the disposable credentials above. |
| Guided example finds existing bookings | Stop the app, reset only the local Compose volume, and restart. Startup intentionally preserves state. |
| Batching considers zero | Normal runtime waits for closed windows or the five-minute urgency guard. The fixture profile uses a 20-minute guard; it still respects window grouping and expiration. |
| Offer expired / route infeasible | Do not reuse an old offer ID. Inspect the trip and advance where an untried feasible driver remains, or reset for a fresh scenario. |
| HTTP 400 in a browser | Open `/` for the request console or `/swagger-ui/index.html` for Swagger on `127.0.0.1`. If Tomcat reports request parsing failure before the app runs, try a clean browser session or clear localhost cookies. |

Run `./mvnw clean verify` (Windows: `.\mvnw.cmd clean verify`) for the build and full tests. Tests use disposable Testcontainers PostgreSQL, not this Compose database, and require no fixture startup.
