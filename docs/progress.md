# Project Progress

This document tracks the actual state of the repository, reflecting features that are concretely designed, implemented, and tested.

## Capability Status

| Capability             | Designed | Implemented | Tested | Notes |
| :---                   | :---:    | :---:       | :---:  | :--- |
| project bootstrap      | D        | I           | T      | Maven, Spring Boot 3.3, Java 21, CI Pipeline |
| PostgreSQL environment | D        | I           | T      | Docker Compose, Testcontainers, Flyway |
| booking                | D        | I           | T      | Basic creation API and persistence |
| deduplication          | D        | I           | T      | Deterministic keys, DB race handling |
| serviceability         | D        | I           | T      | Geospatial point-in-polygon matching |
| batching               | —        | —           | —      | |
| SLA                    | —        | —           | —      | |
| detour                 | —        | —           | —      | |
| driver dispatch        | —        | —           | —      | |
| late insertion         | —        | —           | —      | |
| cancellation           | —        | —           | —      | |
| authentication         | —        | —           | —      | |
| observability          | —        | —           | —      | |
| caching                | —        | —           | —      | |
| failure handling       | —        | —           | —      | |

**Legend:**
* **—** : not started
* **D** : designed
* **I** : implemented
* **T** : tested

## Delivery Sequence

The following flowchart indicates where we currently are in the overall delivery sequence.

```mermaid
flowchart TD
    Phase1[Phase 1: Infrastructure] --> Phase2[Phase 2: Booking Lifecycle]
    Phase2 --> Phase3[Phase 3: Batching & SLA]
    Phase3 --> Phase4[Phase 4: Advanced Operations]
    Phase4 --> Phase5[Phase 5: Hardening]

    style Phase1 fill:#d4edda,stroke:#28a745,color:#155724
    style Phase2 fill:#f8f9fa,stroke:#6c757d
    style Phase3 fill:#f8f9fa,stroke:#6c757d
    style Phase4 fill:#f8f9fa,stroke:#6c757d
    style Phase5 fill:#f8f9fa,stroke:#6c757d
```
