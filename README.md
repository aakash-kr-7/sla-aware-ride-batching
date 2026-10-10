# SLA-Aware Ride Batching

Java Spring Boot backend for the On-Demand Ride Batching case study: combine employee ride requests into shared trips while checking capacity, detour and pickup deadlines.

The repository implements booking, planning, dispatch and trip mutation, PostgreSQL persistence, JWT authorization, zone caching and metrics. Some case-study flows remain incomplete; see the limitations below.

## Reviewer reading order

1. This README: problem, code navigation, build and local setup.
2. [Architecture](docs/architecture.md), then [domain model](docs/domain-model.md): boundaries, booking/offer flow, persisted relationships and reachable states.
3. [Assumptions](docs/assumptions.md) and [batching strategy](docs/batching-strategy.md): policy interpretations, insertion and localized cancellation.
4. [Failure modes](docs/failure-modes.md) and [API contract](docs/api-design.md): transaction/access boundaries and operational limits.
5. [Test matrix](docs/test-matrix.md), then [algorithm analysis](docs/algorithm-analysis.md): evidence and cost bounds.

Each page owns a distinct topic. Decision records explain specific trade-offs; they do not repeat the full architecture. Case-study requirements describe expected behavior, implementation decisions describe code choices, assessment approximations describe model limits, and future improvements describe work not present in this repository.

## Start with the important logic

| Question | Code | Design context |
| --- | --- | --- |
| How does one request become one booking? | [BookingService](src/main/java/com/ridebatching/booking/BookingService.java), [DeduplicationKeyGenerator](src/main/java/com/ridebatching/booking/DeduplicationKeyGenerator.java) | [Deduplication](docs/decisions/0005-handle-booking-deduplication.md) |
| How are trips formed? | [BatchingService](src/main/java/com/ridebatching/batching/BatchingService.java), [BatchPlanner](src/main/java/com/ridebatching/batching/BatchPlanner.java) | [Batching flow](docs/batching-strategy.md) |
| What makes an insertion feasible? | [TripFeasibilityEvaluator](src/main/java/com/ridebatching/batching/evaluator/TripFeasibilityEvaluator.java), [constraints](src/main/java/com/ridebatching/batching/constraint) | [Assumptions](docs/assumptions.md) |
| How are routes changed? | [RouteInsertionPlanner](src/main/java/com/ridebatching/trip/RouteInsertionPlanner.java), [Trip](src/main/java/com/ridebatching/trip/Trip.java) | [Trip mutations](docs/batching-strategy.md) |
| How are drivers selected? | [DispatchService](src/main/java/com/ridebatching/dispatch/DispatchService.java), [driver policies](src/main/java/com/ridebatching/driver) | [Dispatch lifecycle](docs/architecture.md) |
| Where are geographic rules? | [ServiceabilityService](src/main/java/com/ridebatching/zone/ServiceabilityService.java), [geo](src/main/java/com/ridebatching/geo) | [Zone caching](docs/decisions/007-zone-caching.md) |

## Repository map

```text
.github/workflows/       CI: clean verify on Java 21
.mvn/wrapper/            Maven distribution configuration
src/main/java/com/ridebatching/
  booking/              ingestion, deduplication, retrieval and cancellation
  batching/             orchestration, planner, window and cluster algorithms
    constraint/         capacity, pickup SLA and detour policies
    evaluator/          ordered feasibility checks and rejection traces
    api/                manual batching endpoint
  trip/                 route aggregate, stop ordering and insertion search
  driver/               driver state, eligibility and proximity ranking
  dispatch/             offers, rejection cool-off and assignment
  zone/                 circular serviceability checks and cached lookup
  fleet/                vehicle and capacity values used during planning
  geo/                  coordinates, distance, bearing and ETA estimates
  auth/                 accounts, JWTs and Spring Security
  escalation/           records and read-only inspection API
  api/                  shared HTTP errors, pagination and correlation IDs
  config/               properties, cache and OpenAPI wiring
  dev/                  opt-in local admin bootstrap
src/main/resources/     application profiles and Flyway migrations
src/test/               unit and PostgreSQL integration tests
src/benchmark/java/     JMH source, only compiled with benchmark profile
docs/                   design, API contracts, limits and test evidence
```

Controllers and DTOs stay inside their feature. Shared HTTP concerns have one explicit `api` package. Small features keep domain classes and repositories together instead of adding empty layer folders.

## Build and test

Requires JDK 17 or newer and a running Docker daemon. Maven is supplied by the wrapper. The POM targets Java 17; CI runs on Java 21.

```sh
./mvnw test
./mvnw clean verify
```

On PowerShell use `./mvnw.cmd test` and `./mvnw.cmd clean verify`. `verify` runs tests and packages the executable JAR under `target/`. Tests use disposable PostgreSQL 16 containers, including migration validation; they do not use the Compose database or H2.

See [test coverage and gaps](docs/test-matrix.md) and [contribution conventions](CONTRIBUTING.md).

## Run locally

1. Copy `.env.example` to `.env`. Supply `DB_PASSWORD` and a random `JWT_SECRET` of at least 32 characters. The populated file is ignored by Git.
2. Start PostgreSQL: `docker compose up -d`. Check it with `docker compose ps`.
3. Export the same values into the app environment. Compose loads `.env`; Spring Boot does not load it automatically.

PowerShell can import this repository's simple `NAME=value` file without executing it:

```powershell
Get-Content .env | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
./mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

In a POSIX shell, export the variables before `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.

The DB port is bound to loopback. `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET` and `SERVER_PORT` configure connections and secrets. Operational settings in [application.yml](src/main/resources/application.yml) support environment overrides, e.g. `APP_SLA_PICKUP_SLA_MINUTES`.

Admin creation is disabled by default. For an empty account table, set `LOCAL_ADMIN_ENABLED=true`, `LOCAL_ADMIN_USERNAME` and `LOCAL_ADMIN_PASSWORD`, then start with the `local` profile. It creates only an admin; employee identities and serviceable zones must be provisioned separately. There is no destructive demo reseeding profile.

The app listens on port 8080 by default. Open [Swagger UI](http://localhost:8080/swagger-ui/index.html), [OpenAPI JSON](http://localhost:8080/v3/api-docs) or [health](http://localhost:8080/actuator/health). Supported endpoints and examples are in [API design](docs/api-design.md).

`docker compose down` preserves the database volume. `docker compose down -v` deletes local database data.

## Design and operational notes

Decision records cover [dedup transaction boundaries](docs/decisions/0005-handle-booking-deduplication.md), [leader-based clustering](docs/decisions/0006-request-clustering.md) and [zone caching](docs/decisions/007-zone-caching.md). [Historical benchmarks](docs/benchmarks/batching.md) retain dated measurements rather than asserting current performance.

Deduplication and trip mutation rely on PostgreSQL constraints and transactions. Only active-zone lookups are cached. Routing uses local distance/speed estimates rather than live road-network data.

## Current limitations

- Window slots and leader clustering have unit coverage, but `formBatches()` claims all pending bookings and sends them directly to the planner. Window/zone/direction partitioning is not wired into that cycle.
- Scheduling is off by default. When enabled it uses a fixed delay (default 5 seconds), independently of slot length.
- Infeasible solo bookings remain pending. Escalation inspection exists, but no automatic producer/resolution flow exists.
- Dispatch is callable through services/tests. There are no driver, trip-tracking, zone-management or fleet-management HTTP endpoints, and batching does not automatically dispatch offers.
- Cancellation removes/resequences only the affected trip's stops. Feasibility revalidation, driver/offer revocation and prevention of cancellation after pickup remain gaps.
- SLA estimates exclude driver approach time. Seat capacity bounds concurrent riders, not total stops.
- Error mapping and JWT filtering have limits described in the failure notes. Offer expiration and trip execution have no automatic flow.

These boundaries describe the implementation, not production readiness or complete case-study coverage.
