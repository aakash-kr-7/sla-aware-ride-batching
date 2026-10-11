# Run locally

There are two startup paths. **Use the disposable fixture setup to review the project.** Use normal configuration when supplying your own database and reference data. Neither requires a system Maven installation.

## A fresh clone with usable accounts

Install JDK 17 or later and Docker with Compose; start Docker. Python 3.10+ is needed only for the HTTP walkthrough. Ports **8080** (application) and **55433** (local PostgreSQL) must be free.

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

Open the [browser walkthrough](http://127.0.0.1:8080/) to exercise the workflow one click at a time, or [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html) for the complete API. The [page guide](reviewer-ui.md) explains its eight stages and inspection controls. Choose the browser path or the Python path on a fresh database; both save real business state.

For the automated Python path, in a second terminal, from the repository root:

```sh
python scripts/local-review.py
```

If Python 3 is named `python3` on your system, substitute that command. A successful run prints `PASS` after verifying real HTTP responses. It creates **five bookings**, checks duplicate and out-of-zone behaviour, proves **four-plus-one**, inspects capacity/SLA rejection, rejects then accepts driver offers, and cancels one rider while comparing the unrelated trip before/after. Actual responses are written to `target/local-review/`; tokens are not saved. After cancellation, the shared trip has **three** riders. The earlier four-rider state is in `04-four-plus-one.json`.

For a short live explanation, run `python scripts/local-review.py --pause` **instead**, on a fresh database. It pauses at four points so you can inspect Swagger and capture screenshots. Keep the walkthrough within about ten minutes: pickup deadlines still apply, and a live offer can become infeasible. The [review guide](reviewer-guide.md) explains what to show.

## What the fixtures contain

All accounts below use **`local-only-password`**. These are disposable local credentials.

| Identity | Role / employee ID |
| --- | --- |
| `local-admin` | ADMIN; no employee association |
| `local-rider-1` through `local-rider-5` | EMPLOYEE; `00000000-0000-0000-0000-000000000001` through `00000000-0000-0000-0000-000000000005` |

The loader creates five employees, six BCrypt-backed accounts, two active pickup zones and four drivers. **It creates no bookings or trips.** Restarting retains existing rides and driver state; it does not reset them.

| Reference | Configuration |
| --- | --- |
| Central pickup zone | Centre `(12.9716, 77.5946)`, radius 5 km |
| Northern pickup zone | Centre `(13.0716, 77.5946)`, radius 3 km |
| Driver `00000000-0000-0000-0000-000000000065` | Available, four seats, at the central pickup; decimal ID 101 |
| Driver `00000000-0000-0000-0000-000000000066` | Available, four seats, at `(12.9800, 77.5946)`; backup |
| Driver `00000000-0000-0000-0000-000000000067` | Available, two seats; cannot serve the four-rider route |
| Driver `00000000-0000-0000-0000-000000000068` | Offline, four seats; ineligible |

The walkthrough uses the central pickup and destination `(12.8000, 77.5946)`. Different employees make the five requests logically distinct. The long common journey prevents seat turnover before the remaining pickup promises, so four seats produce a real four-plus-one split. Shorter journeys with generous deadlines can legitimately serve five bookings in one trip.

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

Then restart the application with `local-fixtures` and rerun the Python command. Removing the volume deletes these disposable rides and accounts. It does not target the normal Compose project or any private setup.

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

Normal startup creates no employees, accounts, zones or drivers. There is no reference-data CRUD/registration API. The separate `local` profile can create only an admin, on an empty account table, when `LOCAL_ADMIN_ENABLED=true` and username/password are supplied; start with `-Dspring-boot.run.profiles=local`. The complete reviewer setup above is the easiest public path to usable booking data.

## Common failures

| Symptom | Action |
| --- | --- |
| Docker unavailable | Start Docker and verify `docker info`; Compose and PostgreSQL integration tests need it. |
| Port already used | Stop the process using 8080 or the local database using 55433. Changing ports also requires matching application/script configuration. |
| Login fails | Confirm `local-fixtures` was selected and the fixture-loaded log appeared; use the disposable credentials above. |
| Walkthrough finds existing bookings | Stop the app, reset only the local Compose volume, and restart. Startup intentionally preserves state. |
| Batching considers zero | Normal runtime waits for closed windows or the five-minute urgency guard. The reviewer profile uses a 20-minute guard; it still respects window grouping and expiration. |
| Offer expired / route infeasible | Do not reuse an old offer ID. Inspect the trip and use Advance where an untried feasible driver remains, or reset for a repeatable walkthrough. |
| HTTP 400 in a browser | Open `/` for the reviewer page or `/swagger-ui/index.html` for Swagger on `127.0.0.1`. If Tomcat reports request parsing failure before the app runs, try a clean browser session or clear localhost cookies. |

Run `./mvnw clean verify` (Windows: `.\mvnw.cmd clean verify`) for the build and full tests. Tests use disposable Testcontainers PostgreSQL, not this Compose database, and require no fixture startup.
