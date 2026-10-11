# Concurrency, Failure and Operational Boundaries

## Transaction ownership

Correctness relies on PostgreSQL constraints/locks, not JVM-local locks. There are no entity version fields or distributed locks. The table describes current coordination and its scope; it does not establish all possible race safety.

| Case | Mechanism and outcome | Evidence / boundary |
| --- | --- | --- |
| Duplicate creation | One creation transaction uses targeted `ON CONFLICT DO NOTHING`; active-only lookup returns the winner with 201. Late insertion commits with creation. | [BookingConcurrencyIntegrationTest](../src/test/java/com/ridebatching/booking/BookingConcurrencyIntegrationTest.java): ten synchronized callers, one new row, same ID. [BookingMutationRegressionIntegrationTest](../src/test/java/com/ridebatching/booking/BookingMutationRegressionIntegrationTest.java) covers cancel/rebook history and creation rollback. |
| Competing batching workers | Each `formBatches` transaction claims PENDING rows using `FOR UPDATE SKIP LOCKED`; trip/stop writes and confirmation commit together. Locked rows are skipped, not waited for. | [ConcurrentBatchingIntegrationTest](../src/test/java/com/ridebatching/batching/ConcurrentBatchingIntegrationTest.java): five workers, ten bookings considered in total, all confirmed. No fairness, optimal grouping or infeasible-pending retry bound follows. |
| Concurrent late insertion | Lock/reload PENDING booking, initial search, lock/reload selected trip and stops, rebuild booking map and repeat feasibility in one transaction. | [TripMutationConcurrencyIntegrationTest](../src/test/java/com/ridebatching/trip/TripMutationConcurrencyIntegrationTest.java): five arrivals compete for one remaining seat; exactly one confirms. Mutation regressions cover added-rider SLA revalidation and stale assigned/cancelled input. |
| Cancellation mutation | One transaction locks booking, checks ownership, cancels it, locks only associated trip and removes/resequences stops. | [BookingCancellationIntegrationTest](../src/test/java/com/ridebatching/booking/BookingCancellationIntegrationTest.java): affected trip loses two stops; another keeps stop count and update timestamp. Mutation regression forces an actual PostgreSQL booking lock wait during concurrent insertion/cancellation. |
| Competing acceptance | One transaction locks/reloads trip, offer and driver in that order, rechecks eligibility/capacity and assigns them. Partial unique index also allows at most one ACCEPTED offer per trip. Failure rolls back offer and assignments. | [DispatchIntegrationTest](../src/test/java/com/ridebatching/dispatch/DispatchIntegrationTest.java): two offers race, one succeeds, loser stays OFFERED. [DispatchMutationRegressionIntegrationTest](../src/test/java/com/ridebatching/dispatch/DispatchMutationRegressionIntegrationTest.java) forces actual DB lock waits for same-driver/different-trip acceptance and acceptance/rejection races. |

Nullable legacy/direct-write dedup keys remain outside the service guarantee. See [ADR 005](decisions/0005-handle-booking-deduplication.md). Trip and booking locks cover the tested mutation interleavings; they do not imply driver race safety or optimal grouping.

## Failure and recovery

| Failure | Current behavior | Recovery / remaining gap |
| --- | --- | --- |
| Planning/persistence exception | Batching transaction rolls back associations, trips and stops. Injected save failure leaves bookings pending and unassociated. | Retry via admin trigger or enabled scheduler. Rollback test then retries successfully; it does not simulate process crash, network partition or restore. |
| Infeasible solo route | Booking remains PENDING, no trip is persisted. | No automatic escalation producer; repeated cycles can reconsider it indefinitely. Escalation endpoint only reads separately supplied records. |
| DB unavailable | DB-backed requests can fail; aggregate health includes connectivity. | Restore connectivity and inspect durable state before retry. No application retry/circuit breaker or backup/restore workflow is implemented. |
| Driver rejects / nobody eligible | Reject persists REJECTED; dispatch can return empty. | Explicit caller must re-dispatch. Selection is rechecked under the driver lock; acceptance rechecks eligibility and actual peak route capacity. No notification or response deadline. Cool-off starts at rejection (updated timestamp), not offer creation. |
| Cancellation during execution | PICKED_UP/DROPPED are rejected. Before pickup, route order is preserved after stop removal; empty trips expire outstanding offers and release their driver atomically. | No route feasibility recheck for remaining riders. Do not interpret it as complete execution-time replanning. |
| Zone changed outside annotated repository writes | Local cached list can remain stale until expiry, potentially accepting a now-unsupported pickup. | See [cache decision](decisions/007-zone-caching.md) for eviction scope. No cross-instance invalidation or batching-time revalidation. |
| Invalid token / domain exception | Token failures produce non-leaking 401; global handler maps selected domain/validation faults. | JWT filter propagates downstream/database failures. Explicit domain and lock conflicts map to non-leaking 409, ownership denials to 403 and invalid arguments to 400. Missing JWT subject/expiration is invalid. See [API](api-design.md). |

Flyway V8/V10 were corrected during repository hygiene for fresh-schema blockers (wrong employee FK target, existing index name). If an external DB already applied manually repaired versions, compare schema and checksums before upgrading; do not blindly run `flyway repair`. Other migration history is retained.

## Health, metrics and logs

Configured public routes are `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`, `/actuator/metrics` and `/actuator/prometheus`. Only aggregate health includes DB connectivity by default. Info has no custom contributor; environment/beans/configuration endpoints are not exposed. No Kubernetes deployment or alerting pipeline is supplied.

Metrics use Micrometer; candidate rejections are not committed booking counts:

| Metric | What it actually measures |
| --- | --- |
| `bookings.requests`, `bookings.serviceability_rejections` | Service-entry attempts and unsupported-pickup rejections, not successful/unique HTTP bookings |
| `bookings.deduplication_hits` | Active-key conflicts on creation |
| `bookings.cancellation_replans` | Associated-trip cancellation path, including repeat calls; not distinct optimized routes |
| `batching.duration` | Cycle time, including empty cycles and failures |
| `batching.trips_formed` | Planned trip count incremented before transaction commit |
| `batching.sla_rejections`, `batching.detour_rejections` | Rejected candidate evaluations, potentially many for one request |
| `batching.trip_splits` | No fit among existing candidates; incremented before evaluating a solo trip, even if it is rejected |
| `batching.late_insertion` | Success/failure result when planned candidates exist; no-candidate early return is not counted |
| `dispatch.offers`, `dispatch.rejections` | Persist/response paths; offers are not delivered messages, repeat rejection can count again |

Counters are process-local and are not rolled back with database transactions. They cannot prove committed totals or SLA success rates. Labels avoid rider IDs/coordinates; the late-insertion result label has success/failure values.

`CorrelationIdFilter` reads or generates `X-Correlation-Id` and stores it in MDC; it does not return a response header. Console logs include that context; scheduled work has no HTTP context. Logging uses fields in messages, not a JSON encoder. Debug feasibility traces identify the first rejected constraint. Monitoring tests check endpoint availability and scrape format, not alerts, dashboards or metric accuracy during rollback.

## Future improvements

Wider mutation/driver race handling, bounded claim sizes, execution-time cancellation checks, automatic dispatch/escalation and calibrated road ETA remain work. Backup/restore exercises, alerting, private operational endpoints and commit-aware metric interpretation would be production improvements. They are not implied by passing unit/integration tests; see the [test matrix](test-matrix.md).
