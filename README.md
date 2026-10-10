# SLA-Aware Ride Batching

Java Spring Boot backend for the On-Demand Ride Batching case study: combine employee ride requests into shared trips while checking capacity, detour and pickup deadlines.

The repository implements booking, planning, dispatch and trip mutation, PostgreSQL persistence, JWT authorization, zone caching and metrics. Window/clustering orchestration and automatic dispatch/escalation remain incomplete; [requirements traceability](docs/requirements-traceability.md) makes that boundary explicit.

## Reviewer reading order

| Order | Page | Question it answers |
| --- | --- | --- |
| 1 | [Requirements traceability](docs/requirements-traceability.md) | What problem/invariants were requested, and which parts are implemented? |
| 2 | [Architecture](docs/architecture.md), [domain model](docs/domain-model.md) | Where does logic live, how does a booking flow, and which states are reachable? |
| 3 | [Assumptions](docs/assumptions.md), [batching strategy](docs/batching-strategy.md) | Which policies/approximations were chosen, and how are routes accepted or changed? |
| 4 | [Failure modes](docs/failure-modes.md), [API contract](docs/api-design.md) | How are races/rollback handled, what is exposed, and what remains unsafe/incomplete? |
| 5 | [Test matrix](docs/test-matrix.md), [algorithm analysis](docs/algorithm-analysis.md) | What assertions support the invariants, and what does planning cost? |
| 6 | [Reviewer demo](docs/demo.md) | How can I reproduce evidence and make sample API calls? |

Case-study requirements describe expected behavior; implementation decisions describe code choices; assessment approximations describe model limits; future improvements describe absent work. Each page owns its topic. Decision records explain specific trade-offs; dated benchmarks preserve historical measurements.

## Start with the important logic

| Question | Code | Design context |
| --- | --- | --- |
| How does one request become one booking? | [BookingService](src/main/java/com/ridebatching/booking/BookingService.java), [DeduplicationKeyGenerator](src/main/java/com/ridebatching/booking/DeduplicationKeyGenerator.java) | [Deduplication](docs/decisions/0005-handle-booking-deduplication.md) |
| How are trips formed? | [BatchingService](src/main/java/com/ridebatching/batching/BatchingService.java), [BatchPlanner](src/main/java/com/ridebatching/batching/BatchPlanner.java) | [Batching flow](docs/batching-strategy.md) |
| What makes an insertion feasible? | [TripFeasibilityEvaluator](src/main/java/com/ridebatching/batching/evaluator/TripFeasibilityEvaluator.java), [constraints](src/main/java/com/ridebatching/batching/constraint) | [Acceptance rules](docs/batching-strategy.md#insertion-search-and-acceptance) |
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

Follow the [demo setup](docs/demo.md#local-http-walkthrough) for required environment values, PostgreSQL, opt-in admin bootstrap, explicit fixtures and sample requests. Defaults create no accounts or serviceable zones. The app listens on port 8080 unless SERVER_PORT is set; [Swagger UI](http://localhost:8080/swagger-ui/index.html) documents the available HTTP routes.

## Design and operational notes

Decision records cover [dedup transaction boundaries](docs/decisions/0005-handle-booking-deduplication.md), [leader-based clustering](docs/decisions/0006-request-clustering.md) and [zone caching](docs/decisions/007-zone-caching.md). [Historical benchmarks](docs/benchmarks/batching.md) retain dated measurements rather than asserting current performance.

Deduplication and trip mutation rely on PostgreSQL constraints and transactions. Only active-zone lookups are cached. Routing uses local distance/speed estimates rather than live road-network data.

## Current limitations

The implementation checks computed planning constraints but does not complete every case-study flow. [Traceability](docs/requirements-traceability.md) owns coverage; [failure modes](docs/failure-modes.md) owns race/recovery/operational gaps. Straight-line distance with fixed speed/circuity/dwell is an assessment approximation, not a road-network ETA or actual pickup guarantee.
