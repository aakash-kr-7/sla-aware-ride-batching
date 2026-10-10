# ADR 001: Feature-Organized Spring Boot Monolith

Status: Accepted; package map updated to match implementation.

## Decision and rationale

Use one Spring Boot process with feature/domain packages, Maven, PostgreSQL and Flyway. POM targets Java 17; CI uses Java 21. JUnit, AssertJ, Mockito and Testcontainers cover algorithms and persistence.

Batching and trip mutation need coordinated DB changes. A single process and PostgreSQL transactions make those boundaries inspectable without distributed transactions. Algorithms run in memory; repositories own durable state.

| Package | Boundary |
| --- | --- |
| booking | Requests, deduplication and cancellation |
| batching | Orchestration, greedy planner, window/clustering algorithms, feasibility policies |
| trip | Route aggregate and insertion search |
| driver / dispatch | Driver state/selection, offers and assignment |
| zone / geo / fleet | Serviceability, geographic calculations, planning capacity |
| auth / escalation | Authorization and exception-record inspection |
| api | Shared HTTP errors, pagination and correlation context |
| config / dev | Wiring and explicitly enabled local bootstrap |

Controller/service/repository responsibilities remain distinct. Small features keep classes together; subpackages exist where useful. Services access repositories/entities across features, so these are organizational boundaries, not independently enforced modules.

## Infrastructure choices

- PostgreSQL unique indexes and row locks coordinate duplicates, batching and mutations. Mutable operational correctness does not rely on caching.
- Flyway owns schema evolution; Hibernate validates. Migration history remains, including V1 baseline and V3's dedup index superseded by V4.
- Caffeine caches only active zones: [ADR 007](007-zone-caching.md).
- Built-in scheduling is opt-in. No Kafka, Redis or distributed locks are introduced.
- Testcontainers exercises PostgreSQL 16 constraints and native locks. Shared full-application fixture and separate JPA slice serve different boundaries.

## Trade-offs

One process shares CPU/memory/connections across HTTP and planning. No demand/route-size cap or load-test guarantee exists. Greedy planning favours understandable local choices over global optimum; see [complexity](../algorithm-analysis.md).

Package organization does not establish complete case-study coverage; see [current limits](../../README.md#current-limitations).
