# Concurrency, Failure and Operational Boundaries

## Transaction ownership

Correctness relies on PostgreSQL constraints/locks, not JVM-local locks. There are no entity version fields or distributed locks. The table describes current coordination and its scope; it does not establish all possible race safety.

| Case | Mechanism and outcome | Evidence / boundary |
| --- | --- | --- |
| Duplicate creation | Repository `saveAndFlush` transaction; partial unique dedup index chooses winner. Losing transaction ends before lookup; HTTP path returns existing ID with 201. | [BookingConcurrencyIntegrationTest](../src/test/java/com/ridebatching/booking/BookingConcurrencyIntegrationTest.java): ten synchronized callers, one new row, same ID. Cancelled-history lookup is not covered. |
| Competing batching workers | Each `formBatches` transaction claims PENDING rows using `FOR UPDATE SKIP LOCKED`; trip/stop writes and confirmation commit together. Locked rows are skipped, not waited for. | [ConcurrentBatchingIntegrationTest](../src/test/java/com/ridebatching/batching/ConcurrentBatchingIntegrationTest.java): five workers, ten bookings considered in total, all confirmed. No fairness, optimal grouping or infeasible-pending retry bound follows. |
| Concurrent late insertion | Initial search, trip `PESSIMISTIC_WRITE` lock, refresh and repeated feasibility evaluation in one insertion transaction. | [TripMutationConcurrencyIntegrationTest](../src/test/java/com/ridebatching/trip/TripMutationConcurrencyIntegrationTest.java): five arrivals compete for one remaining seat; exactly one confirms. Pre-lock booking map is not rebuilt; status is checked before refresh. |
| Cancellation mutation | One transaction checks employee ownership, cancels booking, locks only associated trip and removes/resequences stops. | [BookingCancellationIntegrationTest](../src/test/java/com/ridebatching/booking/BookingCancellationIntegrationTest.java): affected trip loses two stops; another keeps stop count and update timestamp. Concurrent cancellation/late-insertion is not tested. |
| Competing acceptance | One transaction marks offer accepted, locks trip, guards trip assignment and assigns driver. Partial unique index also allows at most one ACCEPTED offer per trip. Failure rolls back offer and assignments. | [DispatchIntegrationTest](../src/test/java/com/ridebatching/dispatch/DispatchIntegrationTest.java): two offers race, one succeeds, loser stays OFFERED. Driver row is not locked/versioned; same driver accepting different trips concurrently is not proven safe. |

The dedup catch treats every `DataIntegrityViolationException` as a duplicate. Its lookup lacks an active-status filter; repeated cancel/rebook can leave multiple same-key rows. Nullable legacy/direct-write keys are outside the service guarantee. See [ADR 005](decisions/0005-handle-booking-deduplication.md).

Late insertion begins after booking save commits. It can leave a pending booking after an exception; a caller retry may retrieve that row. Its pre-lock booking map can lack a rider added concurrently, causing failed evaluation even after a refreshed route. Mixed races between batching, insertion, cancellation and dispatch need targeted tests; trip locks do not by themselves establish correct stale reads or booking-status transitions.

## Failure and recovery

| Failure | Current behavior | Recovery / remaining gap |
| --- | --- | --- |
| Planning/persistence exception | Batching transaction rolls back associations, trips and stops. Injected save failure leaves bookings pending and unassociated. | Retry via admin trigger or enabled scheduler. Rollback test then retries successfully; it does not simulate process crash, network partition or restore. |
| Infeasible solo route | Booking remains PENDING, no trip is persisted. | No automatic escalation producer; repeated cycles can reconsider it indefinitely. Escalation endpoint only reads separately supplied records. |
| DB unavailable | DB-backed requests can fail; aggregate health includes connectivity. | Restore connectivity and inspect durable state before retry. No application retry/circuit breaker or backup/restore workflow is implemented. |
| Driver rejects / nobody eligible | Reject persists REJECTED; dispatch can return empty. | Explicit caller must re-dispatch. Eligibility uses a snapshot, so concurrent status changes can race with offer creation. No notification, response deadline or automatic expiration. Cool-off starts at offer creation, not rejection. |
| Cancellation during execution | DROPPED is rejected, PICKED_UP is currently accepted. Route order is preserved after stop removal. | No feasibility recheck, driver release or offer revoke. Do not interpret it as complete execution-time replanning. |
| Zone changed outside annotated repository writes | Local cached list can remain stale until expiry, potentially accepting a now-unsupported pickup. | See [cache decision](decisions/007-zone-caching.md) for eviction scope. No cross-instance invalidation or batching-time revalidation. |
| Invalid token / domain exception | Token failures produce non-leaking 401; global handler maps selected domain/validation faults. | JWT filter catches downstream exceptions too, so faults can be mislabeled 401. Other argument/state errors lack consistent mapping; no general 409 contract. See [API](api-design.md). |

Flyway V8/V10 were corrected during repository hygiene for fresh-schema blockers (wrong employee FK target, existing index name). If an external DB already applied manually repaired versions, compare schema and checksums before upgrading; do not blindly run `flyway repair`. Other migration history is retained.

## Health, metrics and logs

Configured public routes are `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`, `/actuator/metrics` and `/actuator/prometheus`. Only aggregate health includes DB connectivity by default. Info has no custom contributor; environment/beans/configuration endpoints are not exposed. No Kubernetes deployment or alerting pipeline is supplied.

Metrics use Micrometer; candidate rejections are not committed booking counts:

| Metric | What it actually measures |
| --- | --- |
| `bookings.requests`, `bookings.serviceability_rejections` | Service-entry attempts and unsupported-pickup rejections, not successful/unique HTTP bookings |
| `bookings.deduplication_hits` | Caught integrity violations on creation; catch is broader than the dedup index |
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
