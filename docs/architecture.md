# Architecture and Request Flow

One Spring Boot process serves HTTP requests and runs planning against PostgreSQL. Feature packages organize code, but services share repositories and entities; they are not independently enforced modules. There is no message broker or external routing service.

```mermaid
flowchart LR
    Client[HTTP client] --> Security[JWT filter and method authorization]
    Security --> Booking[BookingController / BookingService]
    Security --> Admin[Admin batching trigger]
    Scheduler[Opt-in scheduler] --> Batch[BatchingService]
    Admin --> Batch
    Booking --> Batch
    Booking --> Zones[ServiceabilityService]
    Zones --> Cache[Caffeine active-zone list]
    Batch --> Planner[BatchPlanner / route insertion / feasibility]
    Booking --> DB[(PostgreSQL)]
    Batch --> DB
    Zones --> DB
    Caller[Explicit service caller or test] --> Dispatch[DispatchService]
    Dispatch --> DB
```

There is deliberately no arrow from batching to dispatch: that integration is absent. Dispatch has service tests but no driver HTTP endpoint or notification transport. Escalations have a read API, with no automatic producer or resolution workflow.

## Code boundaries

| Package | Responsibility / entry point |
| --- | --- |
| `booking` | [BookingService](../src/main/java/com/ridebatching/booking/BookingService.java): ingest, deduplicate, retrieve, cancel |
| `batching` | [BatchingService](../src/main/java/com/ridebatching/batching/BatchingService.java): transactional orchestration; planner, constraints and evaluator are separate algorithm components |
| `trip` | [Trip](../src/main/java/com/ridebatching/trip/Trip.java): ordered-stop aggregate; [RouteInsertionPlanner](../src/main/java/com/ridebatching/trip/RouteInsertionPlanner.java): insertion search |
| `driver`, `dispatch` | Driver state, selection, offers and assignment |
| `zone`, `geo`, `fleet` | Circular serviceability, distance/ETA estimates and non-persisted planning capacity |
| `auth`, `escalation` | Account authentication/authorization and exception-record inspection |
| `api`, `config`, `dev` | Shared HTTP concerns, framework wiring and opt-in local admin bootstrap |

HTTP DTOs normally live beside their feature. Controllers translate requests; services coordinate persistence; domain types guard local state. The escalation read API currently returns entities directly. Algorithm components can be tested without HTTP or a database.

## Booking to trip

```mermaid
flowchart TD
    Request[Authenticated booking request] --> Validate[Validate coordinates, serviceable pickup and employee]
    Validate --> Key[Compute UTC slot, deadline and dedup key]
    Key --> Save[INSERT ON CONFLICT in creation transaction]
    Save -->|Existing active key| Existing[Read and return existing booking]
    Save -->|New booking| Late[Late insertion in same transaction]
    Late -->|Feasible planned trip| Confirm[Persist route and CONFIRMED association]
    Late -->|No fit| Pending[Return PENDING booking]
    Pending --> Trigger[Explicit admin trigger or enabled scheduler]
    Trigger --> Plan[Transactional batch formation]
    Plan -->|Feasible| Confirm
    Plan -->|Even solo infeasible| Remain[Remain PENDING]
```

The HTTP route returns 201 for both a new booking and a duplicate. There is no client-requested time field: creation time supplies the slot and pickup promise. Creation and late insertion commit together; an insertion exception rolls back the new booking. See [concurrency and failure boundaries](failure-modes.md).

## Driver offers

```mermaid
sequenceDiagram
    participant C as Explicit service caller
    participant S as DispatchService
    participant DB as PostgreSQL
    C->>S: dispatchTrip(tripId, firstPickup)
    S->>DB: Load drivers and recent trip-specific rejections
    S->>S: Filter eligibility, rank by Haversine distance
    S->>DB: Persist OFFERED for nearest eligible driver
    S-->>C: Offer, or empty if no eligible driver
    alt Caller rejects
        C->>S: rejectOffer(offerId)
        S->>DB: Set REJECTED
        Note over C,S: A later explicit dispatch call performs re-offering
    else Caller accepts
        C->>S: acceptOffer(offerId)
        S->>DB: Update offer, lock trip, assign trip and driver
        Note over S,DB: One transaction; failure rolls back all changes
    end
```

Driver status remains `AVAILABLE` after assignment; `activeTripId` excludes it from further selection. Selection is advisory until acceptance. Offers have no deadline, expiry scheduler or delivery mechanism. Dispatch does not compare driver capacity with planned occupancy. Detailed state boundaries are in [domain model](domain-model.md).

## Why these choices

- **Spring Boot monolith, feature packages:** one process keeps booking/trip transaction boundaries inspectable. It shares CPU, memory and DB connections between HTTP and planning; no load guarantee follows from that choice.
- **PostgreSQL + Flyway + JPA:** database indexes and row locks arbitrate races; migrations own schema, Hibernate validates it. This avoids correctness depending on a process-local lock or cache. Tests use actual PostgreSQL 16 via Testcontainers, not H2.
- **Greedy insertion + separate constraints:** small components expose why a route is accepted or rejected. Local cost choices are inspectable, but do not find a global optimum or every feasible insertion. See [planning strategy](batching-strategy.md).
- **Caffeine for zones only:** zone reads are frequent and the data changes less often than trip state. Freshness is TTL-bounded; mutable booking/assignment guarantees stay in PostgreSQL. See [cache decision](decisions/007-zone-caching.md).
- **Explicit transaction boundaries:** duplicates need a usable transaction after a losing insert; see [dedup decision](decisions/0005-handle-booking-deduplication.md). Batching, cancellation and acceptance each coordinate their own changes.

Java 17 is the build target; CI runs Java 21. No distributed infrastructure is needed for this submission. Production routing, end-to-end dispatch and wider race coverage would be further work, not properties of the current architecture.
