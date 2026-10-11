# Concurrency, failure and recovery

The design keeps logical identity and assignment correctness in PostgreSQL. This guide explains the locks and constraints, what commits when an operation fails, and what an operator can inspect next. The [public review](reviewer-guide.md) shows state isolation; the integration tests below exercise contention deliberately.

## Transaction ownership

Correctness relies on PostgreSQL constraints/locks, not JVM-local locks. Driver has an optimistic-lock version for stale detached saves; service assignment also uses row locks. There are no distributed locks. The table describes current coordination and its scope; it does not establish all possible race safety.

| Case | Mechanism and outcome | Evidence / boundary |
| --- | --- | --- |
| Duplicate creation | One creation transaction uses targeted `ON CONFLICT DO NOTHING`; active-only lookup returns the winner with 201. Late insertion commits with creation. | [BookingConcurrencyIntegrationTest](../src/test/java/com/ridebatching/booking/BookingConcurrencyIntegrationTest.java): ten synchronized callers, one new row, same ID. [BookingMutationRegressionIntegrationTest](../src/test/java/com/ridebatching/booking/BookingMutationRegressionIntegrationTest.java) covers cancel/rebook history and creation rollback. |
| Competing batching workers | Each `formBatches` transaction claims bounded eligible PENDING rows using `FOR UPDATE SKIP LOCKED`; trip/stop writes and confirmation commit together. Locked rows are skipped, not waited for. | [ConcurrentBatchingIntegrationTest](../src/test/java/com/ridebatching/batching/ConcurrentBatchingIntegrationTest.java): one worker holds three rows in planning while another commits three different rows; each booking has one associated trip and exactly two stops. Five workers also process ten rows once. No fairness or optimal grouping follows. |
| Concurrent late insertion | Lock/reload PENDING booking, initial search, lock/reload selected trip and stops, rebuild booking map and repeat feasibility in one transaction. | [TripMutationConcurrencyIntegrationTest](../src/test/java/com/ridebatching/trip/TripMutationConcurrencyIntegrationTest.java): five arrivals compete for one remaining seat; exactly one confirms. Mutation regressions cover added-rider SLA revalidation and stale assigned/cancelled input. |
| Cancellation mutation | Lock booking then its trip, reject execution before mutation; remove/resequence stops, recompute estimates and persist remaining pickup risk in one transaction. | [BookingLifecycleHttpIntegrationTest](../src/test/java/com/ridebatching/booking/api/BookingLifecycleHttpIntegrationTest.java) compares unrelated route/arrival/timestamp state, stable risk identity and forced pickup/cancellation lock waits. Existing mutation regressions force insertion/cancellation contention. |
| Competing acceptance | One transaction locks/reloads trip, offer and driver in that order, rechecks eligibility/capacity and assigns them. Partial unique index also allows at most one ACCEPTED offer per trip. Failure rolls back offer and assignments. | [DispatchIntegrationTest](../src/test/java/com/ridebatching/dispatch/DispatchIntegrationTest.java): two legacy offers race, one succeeds and the other expires. [DispatchMutationRegressionIntegrationTest](../src/test/java/com/ridebatching/dispatch/DispatchMutationRegressionIntegrationTest.java) forces actual DB lock waits for same-driver/different-trip acceptance and acceptance/rejection races. |

Driver offers do not reserve a driver across trips. New acceptance rechecks current eligibility, capacity, route structure, pickup SLA and detour under the driver lock, using current time plus approach. It persists refreshed arrival estimates only with successful assignment. Infeasibility returns 409 ROUTE_INFEASIBLE and rolls back; [timing evidence](planner-and-eta.md).

V11 makes dedup keys non-null and adds assignment foreign keys and deferred route uniqueness constraints. See [ADR 005](decisions/0005-handle-booking-deduplication.md). Trip and booking locks cover the tested mutation interleavings; they do not imply fairness or optimal grouping.

## Failure and recovery

| Failure | Current behavior | Recovery / remaining gap |
| --- | --- | --- |
| Planning/persistence exception | Batching transaction rolls back associations, trips, stops and initial offers. Injected trip-save or offer-save failure leaves bookings pending and unassociated. | Retry via admin trigger or enabled scheduler. Rollback test then retries successfully; it does not simulate process crash, network partition or restore. |
| Infeasible solo pickup promise | Expired unassociated PENDING bookings enter a separately bounded SKIP LOCKED pass; persist one OPEN missed-promise issue, no trip. | [BookingSlaEscalationIntegrationTest](../src/test/java/com/ridebatching/batching/BookingSlaEscalationIntegrationTest.java) proves repeat/concurrent uniqueness, bounded recovery and equality. ADMIN/APPROVER inspect issues; cancellation resolves them. No automatic cab/service remedy or manual-resolution API. |
| DB unavailable | DB-backed requests can fail; aggregate health includes connectivity. | Restore connectivity and inspect durable state before retry. No application retry/circuit breaker or backup/restore workflow is implemented. |
| Driver rejects / nobody eligible | Rejection persists REJECTED and attempts the next untried eligible driver immediately. No driver leaves PLANNED with NO_ELIGIBLE_DRIVER, DRIVERS_EXHAUSTED or ATTEMPT_LIMIT_REACHED. | No notifications or escalation producer. Offer lifetime is two minutes, maximum three attempts by default; attempted drivers never repeat. Explicit Advance considers newly available untried drivers without resetting history. See [workflow](dispatch-workflow.md). |
| Expired offer / late acceptance | Expiry job or Advance expires a due offer and considers the next driver within the attempt limit. Acceptance at/after the stored deadline returns 409 OFFER_EXPIRED; expiry/next-offer processing commits intentionally. | Deadline checks protect acceptance even if the job is delayed/disabled. Read-only GET can report OFFER_EXPIRED without mutating state. Cancelled-trip acceptance returns 409 STATE_CONFLICT; successful earlier acceptance is released by final-rider cancellation. HTTP tests force that race. |
| Cancellation during execution | First pickup makes the whole trip IN_PROGRESS; all cancellation is rejected, including for CONFIRMED riders. Before execution, remaining arrivals include approach from the assigned driver if any; capacity/detour violations roll back, and missed pickup deadlines are escalated. | Supported boundary is explicitly pre-execution; no execution-time route mutation. [Lifecycle policy and tests](booking-lifecycle.md). |
| Zone changed outside repository writes | Direct SQL/other-process changes can remain stale until expiry; slow read publication after a competing commit also remains a coherence limit. Repository-write eviction is deferred until commit. | See [cache decision](decisions/007-zone-caching.md) for eviction scope. No cross-instance invalidation; batching grouping revalidates using the same cached active zones. |
| Invalid token / domain exception | Token failures produce non-leaking 401; global handler maps selected domain/validation faults. | JWT filter propagates downstream/database failures. Explicit domain and lock conflicts map to non-leaking 409, ownership denials to 403 and invalid arguments to 400. Missing JWT subject/expiration is invalid. See [API](api-design.md). |

Fresh databases apply all Flyway migrations without manual repair. Older installations with a manually amended V8/V10 must compare schema and checksums before upgrading; do not blindly run `flyway repair`. V11 requires existing null dedup keys, dangling assignment IDs and duplicate route rows to be repaired explicitly. V14 likewise rejects duplicate OPEN escalation references/reasons. These migrations preserve history rather than inventing identities or discarding inconsistent rows. V15 adds nullable arrival estimates without fabricating historical times.

## Health, metrics and logs

Configured public routes are `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`, `/actuator/metrics` and `/actuator/prometheus`. Only aggregate health includes DB connectivity by default. Info has no custom contributor; environment/beans/configuration endpoints are not exposed. No Kubernetes deployment or alerting pipeline is supplied.

Metrics use Micrometer; candidate rejections are not committed booking counts:

| Metric | What it actually measures |
| --- | --- |
| `bookings.requests`, `bookings.serviceability_rejections` | Service-entry attempts and unsupported-pickup rejections, not successful/unique HTTP bookings |
| `bookings.deduplication_hits` | Active-key conflicts on creation |
| `bookings.cancellation_replans` | Newly applied associated-trip cancellation path; idempotent repeats excluded, not distinct optimized routes |
| `batching.duration` | Cycle time, including empty cycles and failures |
| `batching.trips_formed` | Planned trip count incremented before transaction commit |
| `batching.sla_rejections`, `batching.detour_rejections` | Rejected operational route evaluations in insertion, offering and new acceptance; potentially many for one request. Read-only inspection/readiness checks are excluded |
| `batching.trip_splits` | No fit among existing candidates; incremented before evaluating a solo trip, even if it is rejected |
| `batching.late_insertion` | Success/failure result when planned candidates exist; no-candidate early return is not counted |
| `dispatch.offers`, `dispatch.rejections`, `dispatch.acceptances`, `dispatch.expirations` | Persist/response paths; offers are not delivered messages; an idempotent rejection retry does not count again |

Counters are process-local and are not rolled back with database transactions. They cannot prove committed totals or SLA success rates. Labels avoid rider IDs/coordinates; the late-insertion result label has success/failure values.

`CorrelationIdFilter` reads or generates `X-Correlation-Id` and stores it in MDC; it does not return a response header. Console logs include that context; scheduled work has no HTTP context. Logging uses fields in messages, not a JSON encoder. Debug feasibility traces identify the first rejected constraint. Monitoring tests check endpoint availability and scrape format, not alerts, dashboards or metric accuracy during rollback.

## Future improvements

Execution-time route replanning, driver notifications/authentication, operational remedies for SLA issues, stronger zone-cache coherence and calibrated road ETA remain work. Expired requests are retained pending, excluded from planning claims and automatically escalated; cancellation closes their automatic issues. See [lifecycle policy](booking-lifecycle.md). Backup/restore exercises, alerting, private operational endpoints and commit-aware metric interpretation would be production improvements. They are not implied by passing unit/integration tests; see the [test matrix](test-matrix.md).
