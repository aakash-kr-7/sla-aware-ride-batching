# Correctness Review — 11 October 2026

Reviewed booking/planning/dispatch transactions, domain transitions, JPA mappings and migrations, authentication/authorization, timing, errors and test assertions. Findings cite the original implementation at `854f359bc3a8f5719ee973a6ac309417a8cae1c2`; fixes and tests are in the subsequent commits. No Critical issue was found. All eight High findings below are fixed. Twelve Medium and two Low defects are also fixed; deferred concerns have separate severity labels.

## Fixed High findings

| ID / implementation | Failure scenario | Smallest correction applied |
| --- | --- | --- |
| H01 — [BatchingService:92-108][late] and [BookingService:131-142][cancel] | Insertion trusts a detached booking. A cancelled row can become CONFIRMED again, an assigned row can enter a second trip, or concurrent cancellation can be overwritten. | Both mutations lock/reload the booking before the trip. Insertion requires current PENDING status and no trip association. |
| H02 — [BookingService:104-121][create] | Repository save commits before late insertion. An insertion failure returns an error but leaves a durable booking, changing retry behavior. | Creation owns one transaction. Targeted PostgreSQL `ON CONFLICT DO NOTHING` handles duplicate arbitration without aborting that transaction; insertion joins it. |
| H03 — [BatchingService:116-123][revalidate] | Trip state is checked on the stale managed object before refresh. A concurrently dispatched trip can receive another rider. Refreshing deleted child stops can fail; the old booking map omits concurrently added riders. | Reload the aggregate after its row lock, check its current state, rebuild its booking map and re-evaluate every rider. Detach/reload avoids cascading refresh of deleted stops. |
| H04 — [DispatchService:89-110][accept] | One driver accepts offers for two different trips. Trip locks are distinct; both transactions read an unassigned driver and overwrite its active-trip ID. | Lock/reload the driver before eligibility and assignment, using trip → offer → driver order. Exactly one assignment commits. |
| H05 — [DispatchService:78-95][responses] | Acceptance and rejection read the same OFFERED snapshot and both succeed. Final offer state can be REJECTED while its trip and driver are assigned. | Serialize offer transitions under an offer row lock and reload its state before the transition. Losing transition rolls back. |
| H06 — [DispatchService:51-73,106-110][capacity] | Planning assumes four seats; a two-seat driver's offer can be selected and accepted for four simultaneous passengers. | Compare actual driver capacity with peak route occupancy during selection and again under acceptance locks. Turnover is counted by peak occupancy, not total bookings. |
| H07 — [BookingService:139-149][cancel] | Cancelling the final rider marks the trip CANCELLED but leaves the driver excluded forever by activeTripId and leaves outstanding offers live. | In the cancellation transaction, expire only that trip's outstanding offers and clear its driver's matching active-trip reference. |
| H08 — [ServiceableZoneRepository:17-32][zone-writes], [CacheConfig:15-25][cache] | `saveAndFlush`/`deleteById` bypass eviction. Cache publication can expose rolled-back zones; eviction before commit lets a competing read restore a deleted zone. Unsupported pickups can be accepted. | Cover repository write variants and defer cache publication/eviction until commit with Spring's transaction-aware wrapper. Wider coherence limits remain D01 below. |

## Fixed Medium and Low findings

| ID / severity / implementation | Failure scenario | Smallest correction applied |
| --- | --- | --- |
| M01 — Medium — [BookingService:107-113][create] | Repeated cancel/rebook leaves multiple rows with one key; duplicate lookup throws an incorrect-result-size exception. The broad integrity catch can also misclassify unrelated failures. | Read only the active row after targeted conflict arbitration; unrelated constraint failures propagate. |
| M02 — Medium — [RejectionCoolOffPolicy:24-32][cooloff] | An offer created ten minutes ago and rejected now immediately permits the same driver again. | Filter REJECTED offers by updatedAt, which records rejection time. Exact five-minute expiry is eligible. |
| M03 — Medium — [DispatchService:51-73][capacity] | Dispatch creates an offer for a missing or CANCELLED/DISPATCHED trip. | Lock/reload the referenced trip and require PLANNED before selection. |
| M04 — Medium — [Booking.cancel:89-97][booking-state] | A PICKED_UP passenger can be cancelled and removed from the route, including their required dropoff. | Permit cancellation only for PENDING/CONFIRMED; retain CANCELLED idempotency. |
| M05 — Medium — [JwtAuthenticationFilter:61-64][jwt-filter] | Downstream servlet/runtime failures and account DB outages are swallowed and mislabeled 401, obscuring server failures. | Catch only token/account authentication faults around authentication; invoke the downstream chain outside that catch. |
| M06 — Medium — [BookingService:134-137][cancel], [GlobalExceptionHandler:17-49][errors] | Ownership denial is an argument exception; invalid domain transitions have no conflict response. Valid-token requests return misleading 401 or server errors. | Use AccessDeniedException and explicit DomainConflictException; map them to generic 403/409. Lock conflicts also map to 409; unexpected internal failures are not converted to domain conflicts. |
| M07 — Medium — [V4][v4], [V5][v5], [V6][v6], [V7][v7] | Direct/legacy writes can use null dedup keys, dangling assignment IDs, non-positive seats, duplicate booking/type stops across trips or duplicate sequence positions. JPA-only assumptions do not protect the stored state. | Forward migration V11 adds NOT NULL, assignment FKs, positive capacity and deferred route uniqueness constraints. Deferred checks allow valid JPA route replacement/resequencing at commit. |
| M08 — Medium — [Driver:28-32,97-110][driver] | A stale detached location save overwrites a newer driver status/assignment. Row locks in dispatch do not detect a later stale repository merge. | Add Driver's JPA version column; stale saves fail with an optimistic-lock conflict. No retry hides the failure. |
| M09 — Medium — [GeoPoint:8-13][geo] | NaN bypasses range comparisons and propagates into distance/ETA calculations. | Require finite latitude/longitude as well as range bounds. |
| M10 — Medium — [LocalTravelTimeEstimator:15-34][eta] | NaN/zero/negative speed or factors and negative dwell can yield zero/negative or overflowed travel time and invalidate feasibility checks. | Fail at construction unless factors/speed are finite and positive and dwell is non-negative. |
| M11 — Medium — [TripFeasibilityEvaluator:90-104][structure] | Pickup/drop/pickup/drop for one booking passes the onboard-set structural check and yields a misleading feasible trace, though the trip aggregate later rejects it. | Track all picked-up IDs independently of the currently onboard set. |
| M12 — Medium — [Trip.assignRoute:87-97][route] | Passing `trip.getStops()` back into assignRoute clears the backing collection before iteration and silently loses the route. | Copy the input before validation and replacement. |
| L01 — Low — [JwtService:43-49][jwt-claims] | Signed claims missing subject/expiration cause null-pointer failures instead of an invalid verdict. The old filter concealed this as 401. | Validate both required claims and expiration without dereferencing null; parse once for the verdict. |
| L02 — Low — [BatchingWindow:20-30][window] | A positive subsecond Duration divides by zero; a fractional-second duration creates overlapping buckets because alignment truncates seconds. Configured minute windows are unaffected. | Require positive whole seconds, matching the epoch-second algorithm. |

## Regression evidence

The suite grew from 234 to 286 executed tests: 52 new cases; the existing repeated-input assertions were also strengthened. Existing tests were retained; none were disabled or weakened.

- [BookingMutationRegressionIntegrationTest](../src/test/java/com/ridebatching/booking/BookingMutationRegressionIntegrationTest.java): ten cases for H01–H03/H07 and M01/M04. Includes cancellation/insertion and dispatch/insertion lock waits, rollback, stale assigned/cancelled inputs, a concurrently added rider's deadline and isolation of unrelated driver/trip assignment.
- [DispatchMutationRegressionIntegrationTest](../src/test/java/com/ridebatching/dispatch/DispatchMutationRegressionIntegrationTest.java): eight cases for H04–H06 and M02/M03. Holds real PostgreSQL rows until both workers are observed waiting, then asserts one committed outcome and consistent trip/offer/driver state. Also proves exact cool-off expiry and one-seat passenger turnover.
- [DatabaseInvariantRegressionIntegrationTest](../src/test/java/com/ridebatching/DatabaseInvariantRegressionIntegrationTest.java): nine direct database/stale-merge cases for M07/M08. Offer persistence fixtures now use real parent rows rather than nonexistent IDs.
- [ServiceabilityCacheIntegrationTest](../src/test/java/com/ridebatching/zone/ServiceabilityCacheIntegrationTest.java): four added cases for H08, including an independent reader during an uncommitted delete and rollback isolation.
- [JwtAuthenticationFilterTest](../src/test/java/com/ridebatching/auth/JwtAuthenticationFilterTest.java), [JwtServiceTest](../src/test/java/com/ridebatching/auth/JwtServiceTest.java), [SecurityMethodIntegrationTest](../src/test/java/com/ridebatching/auth/SecurityMethodIntegrationTest.java), [GlobalExceptionHandlerTest](../src/test/java/com/ridebatching/api/GlobalExceptionHandlerTest.java): propagation, malformed/expired/wrong-key/missing-claim tokens, forged cancellation identity, ownership denial, admin isolation, state conflicts and non-leaking conflict responses.
- Geo/ETA/window/trip/evaluator unit tests each reproduce the corresponding numeric/route defect. Relevant tests were run before and after correction; the final dispatch-boundary cases extend already passing fixes.

Two test-quality gaps were strengthened without claiming new runtime defects. [ConcurrentBatchingIntegrationTest](../src/test/java/com/ridebatching/batching/ConcurrentBatchingIntegrationTest.java) now holds the first worker inside planning while the second completes with zero claimed rows, then checks persisted stop/association uniqueness. [RoutePlannerInvariantTest](../src/test/java/com/ridebatching/batching/RoutePlannerInvariantTest.java) uses reproducible seeded inputs and independently reconstructs both detour bounds, complete pickup/drop pairing and final occupancy. It remains finite sampling, not exhaustive proof. Database wait polling clears PostgreSQL's transaction-cached statistics snapshot before each observation.

## Intentionally deferred

| ID / severity / implementation | Scenario and reason for deferral | Smallest next improvement |
| --- | --- | --- |
| D01 — Medium — [CacheConfig:15-25][cache] and [zone-cache decision](decisions/007-zone-caching.md) | Other processes/direct SQL do not evict local caches; a slow read can publish an old snapshot after a competing commit. Full serviceability freshness under mutable zones is not proven. No zone mutation API or multi-instance deployment is implemented. A transaction-aware wrapper fixes the reproduced local-write/rollback cases, not distributed coherence. | For strict freshness, bypass caching for admission; otherwise introduce explicit zone-version-aware publication/invalidation. Requires a freshness policy decision rather than an implicit cache redesign. |
| D02 — Low — [BatchingService:94-107][late] and [DispatchService:53-59][capacity] | Lazy stop loading costs one query per candidate trip; cool-off lookup costs one query per eligible driver. Candidate/driver scans are unbounded. This is a performance risk, not a reproduced corrupt-state defect. | Measure query counts, then bulk-fetch stop/history data without changing claim/lock semantics. |
| D03 — Low — [SecurityConfig:38][actuator] and [operational exposure](failure-modes.md#health-metrics-and-logs) | Metrics/Prometheus are deliberately public for the assessment. A public deployment exposes operational metadata; no credential disclosure was found. | Require an operations role or deployment-network access control when a deployment policy exists. |

The cheapest-insertion heuristic, straight-line ETA approximation, missing runtime window/cluster orchestration, automatic dispatch/escalation and execution APIs remain documented assessment limits. They were not turned into invented bug fixes. Pickup SLA equality, all-rider detour/SLA evaluation, existing batching claim locks, password hashing and employee/admin authorization were retained; coverage was strengthened where needed. No dependency replacement, architecture redesign or infrastructure was added.

V11 deliberately fails on invalid legacy data. Existing null keys/dangling references/duplicate stops need explicit repair before upgrading an external database; the review does not invent identities or delete its history. Fresh PostgreSQL migrations are tested.

## Validation and commits

On Java 17 with Docker/PostgreSQL 16 Testcontainers, `./mvnw.cmd --batch-mode test` passed all 286 tests with zero failures/errors/skips. `./mvnw.cmd --batch-mode clean verify` also passed all 286 tests with zero failures/errors/skips and produced the executable JAR.

Passing units were committed and pushed to `origin/main`:

| Commit | Unit |
| --- | --- |
| e137c8f | Atomic booking creation and guarded route mutation |
| 367dbc6 | Driver/offer serialization, capacity and rejection-time cool-off |
| 4517a9d | Empty-trip assignment cleanup and onboard cancellation guard |
| 087a4ee | JWT failure propagation, claims and explicit HTTP conflicts |
| c6db0f1 | V11 persistence invariants and driver version |
| e8d5e39 | Numeric/timing/route input guards |
| ceb9174 | Transaction-aware local zone cache writes |
| d196b9c | Overlapping worker and independent detour evidence |
| 2e34b25 | Dispatch/insertion and exact boundary regression coverage |

[create]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/booking/BookingService.java#L104-L121
[cancel]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/booking/BookingService.java#L131-L149
[late]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/batching/BatchingService.java#L92-L108
[revalidate]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/batching/BatchingService.java#L116-L123
[accept]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/dispatch/DispatchService.java#L89-L110
[responses]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/dispatch/DispatchService.java#L78-L95
[capacity]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/dispatch/DispatchService.java#L51-L110
[zone-writes]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/zone/ServiceableZoneRepository.java#L17-L32
[cache]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/config/CacheConfig.java#L15-L25
[cooloff]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/dispatch/RejectionCoolOffPolicy.java#L24-L32
[booking-state]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/booking/Booking.java#L89-L97
[jwt-filter]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/auth/JwtAuthenticationFilter.java#L61-L64
[errors]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/api/GlobalExceptionHandler.java#L17-L49
[driver]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/driver/Driver.java#L28-L110
[geo]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/geo/GeoPoint.java#L8-L13
[eta]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/geo/LocalTravelTimeEstimator.java#L15-L34
[structure]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/batching/evaluator/TripFeasibilityEvaluator.java#L90-L104
[route]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/trip/Trip.java#L87-L97
[jwt-claims]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/auth/JwtService.java#L43-L49
[window]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/batching/BatchingWindow.java#L20-L30
[actuator]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/java/com/ridebatching/auth/SecurityConfig.java#L38
[v4]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/resources/db/migration/V4__add_deduplication_key_to_bookings.sql
[v5]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/resources/db/migration/V5__create_trip_and_trip_stops.sql
[v6]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/resources/db/migration/V6__create_driver.sql
[v7]: https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/854f359bc3a8f5719ee973a6ac309417a8cae1c2/src/main/resources/db/migration/V7__create_driver_offers.sql
