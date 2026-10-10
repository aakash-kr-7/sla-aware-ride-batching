# ADR 001: Caching Strategy

## Context
As the platform scales, database load must be managed. However, caching mutable state introduces complexity and staleness risks that can compromise operational correctness. We need to evaluate which data is appropriate for caching.

## Analysis of Serviceable Zones
Serviceable zones (`ServiceableZone`) represent geographic polygons/circles where we operate. 

*   **Read Frequency:** High. Every incoming booking requires at least one zone evaluation during ingestion.
*   **Update Frequency:** Very low. Operating zones change rarely (e.g., expanding to a new neighbourhood every few months).
*   **Staleness Risk:** Low. If a zone is deactivated, a temporarily stale cache might allow a handful of bookings in an unsupported area for a few minutes. Given the low update frequency, this is an acceptable operational risk.
*   **Correctness Impact:** Moderate but manageable. Bounding the staleness via TTL limits the blast radius.
*   **Eviction Strategy:** Time-To-Live (TTL) based expiry ensures eventual consistency. We will also implement explicit programmatic eviction triggered by zone updates.
*   **Startup Behaviour:** The cache can easily be hydrated on startup or populated lazily on the first request.

## Explicit Exclusions from Caching
The following domains **must not** rely on an in-memory cache:
*   **Booking Deduplication:** Deduplication relies on atomic database constraints (`DataIntegrityViolationException`) to prevent race conditions when concurrent requests contain identical keys. Caching this would reintroduce race windows across instances.
*   **Trip Feasibility:** Trip evaluations depend on real-time trip states (seats taken, active detours). Stale reads could lead to over-committing a vehicle or breaching SLA bounds.
*   **Transactional Correctness (Trip Formation/Rollback):** The batching engine requires `SELECT ... FOR UPDATE SKIP LOCKED`. Using a cache here would bypass row-level locks, causing distributed anomalies and violating the atomic properties required during trip mutations.

## Decision
We will implement an in-memory, bounded cache for `ServiceableZone` lookups using **Caffeine**.
Since this is a single-process deployment profile (or where eventually consistent zone boundaries are acceptable across multiple instances), a distributed cache like Redis is unnecessary and adds unjustified infrastructure overhead. PostgreSQL remains the absolute source of truth.
