# SLA-aware ride batching

A Java backend for employee ride pooling. It groups compatible requests into shared trips, checks seat capacity, pickup deadlines and detour limits, then coordinates driver offers and assignment.

[Run locally](docs/local-development.md) · [Architecture](docs/architecture.md) · [Requirements and tests](docs/requirements-traceability.md)

## Run and try it

You need JDK 17+ and Docker with Compose. The local profile supplies disposable accounts, pickup zones and drivers; it creates no rides until you send requests.

```sh
git clone https://github.com/aakash-kr-7/sla-aware-ride-batching.git
cd sla-aware-ride-batching
docker compose -f compose.local.yml up -d --wait
```

Keep the application running in a terminal:

```powershell
# Windows PowerShell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local-fixtures'
```

Linux/macOS: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local-fixtures`.

Open the [request console](http://127.0.0.1:8080/), select **Use local credentials**, then **Connect**.

- **Guided example:** five riders, duplicate handling, invalid pickup, a four-plus-one split, driver rejection and acceptance, then cancellation of one rider.
- **Your scenario:** edit up to nine journeys and send requests together. Run batching, inspect the routes, accept or reject offers, and record pickups/dropoffs from each trip. On a fresh database the default long journey normally produces **4 + 4 + 1**. Different inputs can produce different outcomes.
- **[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html):** all endpoints, schemas and error contracts. Its authorization is separate from the request console.

The fixture fleet starts with two four-seat drivers and one two-seat driver available, plus one offline driver. An offer does not reserve a driver: competing trips can initially offer the same cab, and acceptance checks its current availability under locks. There is no driver app; response and execution controls are authenticated admin simulations.

For a repeatable run, use a fresh local database. [Setup, accounts, policy differences and reset commands](docs/local-development.md).

## How it works

![Booking, planning and dispatch in one application](docs/assets/system-overview.svg)

One Spring Boot application owns the workflow. PostgreSQL stores bookings, routes, offers and SLA issues; its constraints and locks coordinate concurrent writes. Caffeine caches the active pickup-zone list.

1. Validate the employee and pickup zone. Deduplicate by employee, normalized endpoints and UTC slot. A new request can join an existing planned trip through late insertion.
2. Claim at most 100 eligible pending requests using `FOR UPDATE SKIP LOCKED`. Partition by source window, pickup zone and compatible direction.
3. Try pickup/dropoff insertion positions while preserving existing stop order. Choose the cheapest insertion that passes capacity, every pickup deadline and every rider's detour limits. If none fits, evaluate a separate trip.
4. Save routes, confirmations and initial offers in one transaction. Driver acceptance rechecks approach time and route feasibility before committing assignment.
5. Before execution, cancellation removes the affected rider's stops and recalculates that route. Ordered pickups and dropoffs advance the trip and release the driver on completion.

Four seats limit **simultaneous passengers**, not total bookings with seat turnover. A solo request whose pickup promise is already missed remains pending with a saved SLA issue. [Planning rules](docs/batching-strategy.md) · [ETA and insertion search](docs/planner-and-eta.md).

## Engineering decisions

| Decision | Reason |
| --- | --- |
| Feature-first Spring Boot monolith | Keep HTTP, domain rules and transaction ownership easy to follow without adding service boundaries |
| PostgreSQL, Flyway and JPA | Use database constraints and row locks for identity and assignment; migrations own the schema |
| Greedy feasible insertion | Make candidate routes and rejection reasons inspectable; accept a longer valid route over a shorter invalid one |
| Caffeine for zones only | Cache reference reads without making mutable assignment correctness depend on cached state |
| Testcontainers and JUnit | Verify real PostgreSQL conflict, locking and rollback behaviour |

A losing duplicate insert returns the existing booking. Batching workers claim separate rows. Late insertion reloads the locked trip before rechecking all riders. Acceptance locks trip, offer and driver. Cancellation changes only its associated route. [Concurrency and recovery](docs/failure-modes.md) links each mechanism to its tests.

Java 17 is the compilation target; CI uses Java 21. Maven Wrapper pins the build tool. The browser uses plain HTML, CSS and JavaScript served by Spring Boot, with no separate frontend build.

## Tests

```powershell
.\mvnw.cmd --batch-mode clean verify
```

Linux/macOS: `./mvnw --batch-mode clean verify`. Docker must be running. Tests create their own PostgreSQL Testcontainers and need no local fixture database. CI runs the same lifecycle.

The suite covers normal requests, boundary inputs, rollback and competing operations, including duplicate creation, overlapping batch workers, late insertion, driver acceptance and cancellation races. [Test matrix](docs/test-matrix.md).

## Repository guide

| Location | Contents |
| --- | --- |
| `src/main/java/com/ridebatching` | Feature packages: booking, batching, trip, dispatch, driver, auth, zone and supporting policies |
| `src/main/resources/db/migration` | Forward Flyway schema migrations |
| `src/main/resources/static` | Request console |
| `src/test` | Unit, database and authenticated HTTP tests |
| `src/benchmark` | Optional JMH harness; [older measurements](docs/benchmarks/batching.md) are not current performance results |
| `docs` | Design, API contracts, setup and evidence |

Start with [architecture](docs/architecture.md) and [domain states](docs/domain-model.md), then [API design](docs/api-design.md), [trip inspection](docs/trip-inspection.md), [dispatch](docs/dispatch-workflow.md) and [booking lifecycle](docs/booking-lifecycle.md). [Assumptions](docs/assumptions.md), [algorithm cost](docs/algorithm-analysis.md) and [requirement traceability](docs/requirements-traceability.md) explain policy choices and their limits. Design records cover [deduplication](docs/decisions/0005-handle-booking-deduplication.md), [clustering](docs/decisions/0006-request-clustering.md) and [zone caching](docs/decisions/007-zone-caching.md).

## Limits

- ETA uses straight-line distance, fixed speed/circuity and dwell. It checks driver approach but does not measure traffic or actual arrivals.
- Greedy grouping does not find a global optimum. Claims bound returned rows, while database scans, fleet scans, late insertion and total route length can still grow.
- Driver responses and execution use admin simulation. Reference-data CRUD, delivered notifications and operational reassignment or SLA-resolution APIs are outside this implementation.
- Zone caching has no cross-instance invalidation. Actuator is public under the assessment configuration. Sustained load, backup/restore and deployment recovery have not been established.

These are the next steps for an operational service; the current project demonstrates the case-study workflow and its database correctness rules.
