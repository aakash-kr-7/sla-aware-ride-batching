# System Failure Modes

This document catalogues realistic operational failures within the SLA-Aware Ride Batching platform, specifically mapping out the existing protections and remaining limitations based on the system as currently implemented.

## 1. Database Unavailable
* **Impact:** The application is unable to process bookings, form trips, dispatch vehicles, or evaluate cache misses. HTTP requests and scheduled jobs will fail.
* **Current Protection:** Spring Boot Actuator exposes database liveness in `/actuator/health/liveness`. Uncommitted transactions are automatically aborted/rolled back by PostgreSQL if the TCP connection drops.
* **Remaining Limitation:** There is no implemented application-level retry/circuit breaker for database outages; the platform halts until PostgreSQL returns.
* **Recovery Behaviour:** Wait for PostgreSQL. Actuator `health` will naturally recover when the DB is reachable. Application state remains consistent due to ACID compliance.

## 2. Application Crash During Batching
* **Impact:** A batching cycle terminates midway through forming routes and associating bookings.
* **Current Protection:** `BatchingService` performs the `formBatches()` cycle inside a `@Transactional` block.
* **Remaining Limitation:** If the instance dies, the scheduled cycle is missed.
* **Recovery Behaviour:** Because the transaction never commits, PostgreSQL automatically rolls back the entire batch. Bookings remain `PENDING`. The next cron trigger (or next instance) will safely pick them up and try again.

## 3. Duplicate HTTP Retry (e.g., Booking Creation)
* **Impact:** The client experiences a network timeout and blindly retries submitting the exact same booking request.
* **Current Protection:** `BookingService` accepts an idempotent deduplication key (e.g., `clientRequestId`). A unique constraint on the database rejects the second `INSERT` with a `DataIntegrityViolationException`.
* **Remaining Limitation:** The current API responds with a generic 409 Conflict rather than smoothly returning the existing booking's ID (strict idempotency). 
* **Recovery Behaviour:** The client must fetch their active bookings to confirm if the initial request succeeded. 

## 4. Two Batching Workers / Overlapping Scheduled Execution
* **Impact:** Multiple instances of the application (or overlapping cron runs on a slow instance) attempt to form batches at the exact same time.
* **Current Protection:** `BatchingService` uses pessimistic locking (`SELECT ... FOR UPDATE SKIP LOCKED` or similar logic via native query) to claim bookings exclusively. A booking is claimed and locked by exactly one worker.
* **Remaining Limitation:** While workers won't corrupt data, they might create sub-optimal fragmented trips (e.g. Worker A takes 2 passengers, Worker B takes 2 passengers, instead of 1 trip with 4).
* **Recovery Behaviour:** The trips are legally formed. The system self-recovers in subsequent cycles.

## 5. Two Competing Driver Acceptances
* **Impact:** Two drivers attempt to accept the same trip offer simultaneously (or a driver accepts while another rejects).
* **Current Protection:** Handled via database constraints or optimistic locking (if implemented on the offer/trip).
* **Remaining Limitation:** Requires explicit locking or state constraints to prevent both drivers from being recorded as the assignee.
* **Recovery Behaviour:** Only the first transaction commits; the second fails.

## 6. Stale or Unavailable Cache
* **Impact:** `ServiceableZone` lookups evaluate incorrectly or fail to resolve.
* **Current Protection:** Caching is bounded and short-lived (5-minute TTL via Caffeine). Any database write to `ServiceableZone` programmatically evicts the cache (`@CacheEvict`).
* **Remaining Limitation:** It is not a distributed cache; in a multi-instance deployment, changing a zone via Instance A will not evict Instance B's local cache. Instance B remains stale for up to 5 minutes.
* **Recovery Behaviour:** Resolves naturally after 5 minutes via TTL expiration.

## 7. Invalid or Expired JWT
* **Impact:** API requests from employees/drivers are rejected.
* **Current Protection:** `JwtAuthenticationFilter` intercepts requests. Expired or malformed tokens yield a 403 Forbidden / 401 Unauthorized before reaching domain logic.
* **Remaining Limitation:** We do not track revoked tokens (no JWT blocklist). If a user is fired, their token remains active until its embedded expiry time is reached.
* **Recovery Behaviour:** User must obtain a new token via `/auth/login`.

## 8. Optimistic-Lock Conflict
* **Impact:** Two concurrent processes attempt to modify the same entity without pessimistic locks (e.g., two admins modifying a Zone simultaneously).
* **Current Protection:** If `@Version` is used on JPA entities, the second transaction throws an `OptimisticLockException`.
* **Remaining Limitation:** The application simply throws a 500 error back to the client. No automatic retry is performed.
* **Recovery Behaviour:** The user must manually refresh their data and attempt the operation again.
