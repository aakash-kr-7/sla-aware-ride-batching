# Requirements Traceability

Source attribution retained from the earlier project review: *MoveInSync Backend Case Study 2026 — Case Study 7: On-Demand Ride Batching*, pages 1–2, sections I–IV and evaluation criteria. The supplied PDF is not vendored; this page paraphrases its requirements. This final pass verifies the implementation, tests and demo against the current repository; it cannot independently recheck the absent PDF. R/P/T labels are repository navigation IDs, not official numbering.

## Problem and coverage rules

Employees submit ad-hoc pickup/dropoff requests. Sharing should reduce unnecessary cabs without trading away pickup promises, seat limits or rider detours. Nearby pickup and direction are grouping hints; feasibility takes precedence. Drivers must be eligible, and one cancellation must not reshuffle unrelated trips. The source's 15-minute window, 20-minute SLA and four-seater example are illustrative; [assumptions](assumptions.md) records the chosen runtime policies.

**Implemented** means the stated scope has code and behavioural evidence. **Partial** identifies missing integration, execution handling or validation. Neither label claims actual road-arrival guarantees. Tests below link directly to source; T IDs connect to the more detailed [test matrix](test-matrix.md). Demo numbers are the captured stage prefixes from [ReviewerDemoTest][demo-test] and the [walkthrough][demo], generated under `target/demo/`.

## Core requirements

| ID / requirement | Implementation and status | Automated evidence | Demo / explicit boundary |
| --- | --- | --- | --- |
| R01 Serviceable pickup | **Implemented at creation.** [BookingService][booking] checks [ServiceabilityService][zones] before insert; active circles, radius inclusion and deterministic overlap selection. | T01: [ServiceabilityServiceTest][zone-test], [ServiceableZoneIntegrationTest][zone-db-test], [BookingControllerTest][booking-http-test]. | [03 / 05][demo]: accepted pickup and 422 outside-zone error. No dropoff policy or batching-time revalidation; zone-cache freshness has limits. |
| R02 One logical booking after double-submit | **Implemented for active service-created requests.** [DeduplicationKeyGenerator][dedup] normalizes employee/endpoints/slot; [BookingRepository][booking-repo] arbitrates inserts against [V4][v4]'s partial unique index; [V11][v11] disallows null keys. | T02: [DeduplicationKeyGeneratorTest][dedup-test], [BookingPersistenceIntegrationTest][booking-db-test], [BookingConcurrencyIntegrationTest][duplicate-race], [BookingMutationRegressionIntegrationTest][booking-races]. | [04][demo]: same ID, listing count one and DB count one. Ten-worker race is a test, not the sequential demo. Direct writers must supply canonical keys. |
| R03 Capacity | **Implemented computed constraint and driver checks.** [TripFeasibilityEvaluator][evaluator] computes peak occupancy; [CapacityConstraint][capacity] checks planning seats; [DispatchService][dispatch] checks actual driver capacity at selection/acceptance. | T03: [CapacityConstraintTest][capacity-test], [BatchPlannerAcceptanceTest][planner-test], [DispatchMutationRegressionIntegrationTest][dispatch-races]. | [07 / 08 / 10][demo]: five-rider proposal rejected, persisted four-plus-one grouping, undersized driver skipped. One booking represents one rider; seats limit simultaneous occupancy. |
| R04 Pickup SLA; split or escalate | **Partial workflow.** [PickupSlaConstraint][sla] checks every pickup deadline; [BatchPlanner][planner] checks a solo route when sharing fails and leaves infeasible solo requests pending. | T04: [PickupSlaConstraintTest][sla-test], [BatchPlannerAcceptanceTest][planner-test], [BookingServiceTest][booking-test]. | [09][demo]: both combined pickup orders rejected, two feasible solo trips persisted. No automatic escalation or driver-approach estimate; no actual arrival guarantee. |
| R05 Rider detour | **Implemented estimated policy.** [DetourConstraint][detour] applies both relative and absolute extra-time bounds to all riders in the candidate route. | T05: [DetourConstraintTest][detour-test], [TripFeasibilityEvaluatorTest][evaluator-test], [RoutePlannerInvariantTest][invariant-test]. | Explicit detour rejection is test evidence, not a separate demo stage. Modelled shared/direct durations use the same local estimator; policy values and road accuracy are assumptions. |
| R06 Eligible nearby driver | **Implemented service behaviour.** [DriverEligibilityPolicy][eligibility] excludes offline/break/active-trip drivers; [DriverProximityRanker][ranker] sorts eligible drivers by Haversine distance then ID; [DispatchService][dispatch] persists offers. | T06: [DriverEligibilityPolicyTest][eligibility-test], [DriverProximityRankerTest][ranker-test], [DispatchIntegrationTest][dispatch-test]. | [10 / 13][demo]: eligible nearest driver offer and consistent accepted assignment. No driver HTTP API, notifications or execution lifecycle. |
| R07 Rejection cool-off and next offer | **Partial workflow.** [RejectionCoolOffPolicy][cooloff] uses rejection-time `updatedAt`; [DispatchService][dispatch] skips recent rejections for the same trip on an explicit later dispatch call. | T07: [DispatchIntegrationTest][dispatch-test], [DispatchMutationRegressionIntegrationTest][dispatch-races] prove exclusion, old-offer rejection timing and exact expiry. | [11 / 12][demo]: rejection, temporary exclusion and backup offer. Demo checks current exclusion, not expiry after waiting. No automatic re-offer loop; five minutes is chosen policy. |
| R08 Cancellation isolation | **Partial execution handling.** [BookingService][booking] locks booking/trip; [Trip][trip] removes two stops and resequences. Empty trips cancel and [DispatchService][dispatch] releases matching assignments/outstanding offers. | T08: [BookingCancellationIntegrationTest][cancel-test], [BookingMutationRegressionIntegrationTest][booking-races], [TripTest][trip-test]. | [15][demo]: affected route changes; full unrelated trip snapshot remains equal. No new optimization or execution-time feasibility recheck; only PENDING/CONFIRMED bookings may be cancelled. |
| R09 Window plus zone/direction clustering | **Partial components; runtime integration absent.** [BatchingWindow][window] supplies creation's UTC dedup slot; [RequestClusterer][cluster] groups standalone inputs. [BatchingService][batching] claims all pending rows without slot/zone/direction filters. | T09: [BatchingWindowTest][window-test], [RequestClustererTest][cluster-test], [BearingTest][bearing-test]. | No runtime demo claim. These component tests do not prove windowed/clustered orchestration; claim size is unbounded. |
| R10 Late add when all constraints still fit | **Implemented for planned trips.** [BatchingService][batching] locks booking then chosen trip, reloads current riders and re-evaluates; [BookingService][booking] keeps creation/insertion atomic. | T10: [LateBookingInsertionIntegrationTest][late-test], [TripMutationConcurrencyIntegrationTest][insertion-race], [BookingMutationRegressionIntegrationTest][booking-races]. | [14][demo]: HTTP create confirms L1 on the planned singleton without a batch trigger. No insertion into dispatched/executing trips; race tests cover one remaining seat and cancellation/dispatch waits. |
| R11 Booking data and progress | **Partial lifecycle.** [Booking][booking-entity], [BookingController][booking-controller], [Trip][trip] persist and expose employee/endpoints/slot/deadline/status/association; service flows reach PENDING, CONFIRMED, CANCELLED. | T11: [BookingCreationIntegrationTest][create-test], [BookingRetrievalIntegrationTest][read-test], [TripTest][trip-test], [RouteInsertionPlannerTest][insertion-test]. | [03 / 04 / 08 / 14 / 15][demo]. No requested-time input, pickup/dropoff execution API or trip-read HTTP API. |

## Evaluation criteria

| ID / criterion | Implementation / evidence | Demo and limits |
| --- | --- | --- |
| P01 Authentication and roles | [SecurityConfig][security], [JwtAuthenticationFilter][jwt-filter], [JwtService][jwt], [AuthController][auth]; T12: [SecurityIntegrationTest][security-test], [SecurityMethodIntegrationTest][ownership-test], [JwtAuthenticationFilterTest][jwt-filter-test], [JwtServiceTest][jwt-test]. | [02][demo]: real BCrypt-backed login/JWT and wrong-employee denial. Claims alone are not the role/ownership authority; current account is reloaded. Public operational endpoints are a declared policy. |
| P02 Algorithm cost | [RouteInsertionPlanner][insertion], [BatchPlanner][planner], [RequestClusterer][cluster]; [analysis](algorithm-analysis.md), [JMH source][benchmark] and [dated synthetic measurements](benchmarks/batching.md). Behaviour: [RouteInsertionPlannerTest][insertion-test], [RoutePlannerInvariantTest][invariant-test]. | Search/cost reasoning is inspectable; no current service latency/load guarantee. JMH is optional and not part of ordinary correctness tests or this demo. |
| P03 Failure/rollback/recovery | Transactions in [BatchingService][batching], [BookingService][booking], [DispatchService][dispatch] and DB constraints in [V11][v11]. T13: [BatchingRollbackIntegrationTest][rollback-test], [ConcurrentBatchingIntegrationTest][batch-race], [DatabaseInvariantRegressionIntegrationTest][db-invariants], [DispatchMutationRegressionIntegrationTest][dispatch-races]. | Demo confirms durable state; injected rollback and forced races are tests. No crash-recovery, backup/restore or failover validation. |
| P04 OOP and layering | [Architecture](architecture.md), [Trip][trip] aggregate, [BookingController][booking-controller]/[BookingService][booking]/[BookingRepository][booking-repo]. Domain evidence: [TripTest][trip-test], [BookingServiceTest][booking-test]. | Feature packages are organizational, not enforced modules. Escalation inspection currently returns entities directly. |
| P05 Trade-offs | [Assumptions](assumptions.md), [strategy](batching-strategy.md), [dedup decision](decisions/0005-handle-booking-deduplication.md), [clustering decision](decisions/0006-request-clustering.md), [cache decision](decisions/007-zone-caching.md). Their runtime choices link to R02/R05/R09/P07 and corresponding tests above/below. | Policy choices are distinguished from source requirements and missing work. No global optimum or road-ETA accuracy claim. |
| P06 Monitoring/logging | [Application exposure settings][settings], Micrometer instrumentation in [BookingService][booking]/[BatchingService][batching]/[TripFeasibilityEvaluator][evaluator]/[DispatchService][dispatch], [CorrelationIdFilter][correlation]. T14: [ObservabilityIntegrationTest][metrics-test], [ReviewerDemoTest][demo-test]. | [01 / 16][demo]: health/readiness, selected counters, timer and Prometheus metric presence. Correlation appears in application logs but has no dedicated header/MDC test. Counters are not transactional success totals; no alerts/dashboard/JSON encoder. |
| P07 Caching and staleness | [CacheConfig][cache], [ServiceableZoneRepository][zone-repo]; T15: [ServiceabilityCacheIntegrationTest][cache-test] covers cached empty lists, write eviction, concurrent delete/reader and rollback cases. | No dedicated demo mutation stage. Local TTL/after-commit eviction does not establish cross-instance/direct-SQL freshness or every publication race; TTL timing/all bulk-write variants are not fully tested. |
| P08 Errors and exceptions | [GlobalExceptionHandler][errors], [JwtAuthenticationFilter][jwt-filter]. T12: [GlobalExceptionHandlerTest][errors-test], [BookingControllerTest][booking-http-test], [SecurityMethodIntegrationTest][ownership-test], [JwtAuthenticationFilterTest][jwt-filter-test]. | [02 / 05][demo]: authorization and useful domain rejection. Explicit 400/403/404/409/422 contracts; invalid JWT is 401, missing auth follows Spring's 403 policy. Unexpected failures remain server errors. |

The [walkthrough][demo] is the submission demonstration; [ReviewerDemoTest][demo-test] runs the assertions against the actual HTTP server and PostgreSQL. No recorded video/screenshots are committed. CI executes `clean verify` followed by the isolated demo. Git history is retained; the [correctness review](correctness-review.md) records historical defects/fixes rather than redefining current feature scope.

[booking]: ../src/main/java/com/ridebatching/booking/BookingService.java
[booking-repo]: ../src/main/java/com/ridebatching/booking/BookingRepository.java
[booking-entity]: ../src/main/java/com/ridebatching/booking/Booking.java
[booking-controller]: ../src/main/java/com/ridebatching/booking/api/BookingController.java
[dedup]: ../src/main/java/com/ridebatching/booking/DeduplicationKeyGenerator.java
[zones]: ../src/main/java/com/ridebatching/zone/ServiceabilityService.java
[zone-repo]: ../src/main/java/com/ridebatching/zone/ServiceableZoneRepository.java
[cache]: ../src/main/java/com/ridebatching/config/CacheConfig.java
[batching]: ../src/main/java/com/ridebatching/batching/BatchingService.java
[planner]: ../src/main/java/com/ridebatching/batching/BatchPlanner.java
[insertion]: ../src/main/java/com/ridebatching/trip/RouteInsertionPlanner.java
[evaluator]: ../src/main/java/com/ridebatching/batching/evaluator/TripFeasibilityEvaluator.java
[capacity]: ../src/main/java/com/ridebatching/batching/constraint/CapacityConstraint.java
[sla]: ../src/main/java/com/ridebatching/batching/constraint/PickupSlaConstraint.java
[detour]: ../src/main/java/com/ridebatching/batching/constraint/DetourConstraint.java
[window]: ../src/main/java/com/ridebatching/batching/BatchingWindow.java
[cluster]: ../src/main/java/com/ridebatching/batching/RequestClusterer.java
[trip]: ../src/main/java/com/ridebatching/trip/Trip.java
[dispatch]: ../src/main/java/com/ridebatching/dispatch/DispatchService.java
[eligibility]: ../src/main/java/com/ridebatching/driver/DriverEligibilityPolicy.java
[ranker]: ../src/main/java/com/ridebatching/driver/DriverProximityRanker.java
[cooloff]: ../src/main/java/com/ridebatching/dispatch/RejectionCoolOffPolicy.java
[security]: ../src/main/java/com/ridebatching/auth/SecurityConfig.java
[auth]: ../src/main/java/com/ridebatching/auth/AuthController.java
[jwt]: ../src/main/java/com/ridebatching/auth/JwtService.java
[jwt-filter]: ../src/main/java/com/ridebatching/auth/JwtAuthenticationFilter.java
[errors]: ../src/main/java/com/ridebatching/api/GlobalExceptionHandler.java
[correlation]: ../src/main/java/com/ridebatching/api/CorrelationIdFilter.java
[settings]: ../src/main/resources/application.yml
[v4]: ../src/main/resources/db/migration/V4__add_deduplication_key_to_bookings.sql
[v11]: ../src/main/resources/db/migration/V11__enforce_assignment_and_route_invariants.sql
[benchmark]: ../src/benchmark/java/com/ridebatching/benchmark/BatchPlannerBenchmark.java
[zone-test]: ../src/test/java/com/ridebatching/zone/ServiceabilityServiceTest.java
[zone-db-test]: ../src/test/java/com/ridebatching/zone/ServiceableZoneIntegrationTest.java
[booking-http-test]: ../src/test/java/com/ridebatching/booking/api/BookingControllerTest.java
[dedup-test]: ../src/test/java/com/ridebatching/booking/DeduplicationKeyGeneratorTest.java
[booking-db-test]: ../src/test/java/com/ridebatching/booking/BookingPersistenceIntegrationTest.java
[duplicate-race]: ../src/test/java/com/ridebatching/booking/BookingConcurrencyIntegrationTest.java
[booking-races]: ../src/test/java/com/ridebatching/booking/BookingMutationRegressionIntegrationTest.java
[booking-test]: ../src/test/java/com/ridebatching/booking/BookingServiceTest.java
[capacity-test]: ../src/test/java/com/ridebatching/batching/constraint/CapacityConstraintTest.java
[planner-test]: ../src/test/java/com/ridebatching/batching/BatchPlannerAcceptanceTest.java
[sla-test]: ../src/test/java/com/ridebatching/batching/constraint/PickupSlaConstraintTest.java
[detour-test]: ../src/test/java/com/ridebatching/batching/constraint/DetourConstraintTest.java
[evaluator-test]: ../src/test/java/com/ridebatching/batching/evaluator/TripFeasibilityEvaluatorTest.java
[invariant-test]: ../src/test/java/com/ridebatching/batching/RoutePlannerInvariantTest.java
[eligibility-test]: ../src/test/java/com/ridebatching/driver/DriverEligibilityPolicyTest.java
[ranker-test]: ../src/test/java/com/ridebatching/driver/DriverProximityRankerTest.java
[dispatch-test]: ../src/test/java/com/ridebatching/dispatch/DispatchIntegrationTest.java
[dispatch-races]: ../src/test/java/com/ridebatching/dispatch/DispatchMutationRegressionIntegrationTest.java
[cancel-test]: ../src/test/java/com/ridebatching/booking/BookingCancellationIntegrationTest.java
[trip-test]: ../src/test/java/com/ridebatching/trip/TripTest.java
[window-test]: ../src/test/java/com/ridebatching/batching/BatchingWindowTest.java
[cluster-test]: ../src/test/java/com/ridebatching/batching/RequestClustererTest.java
[bearing-test]: ../src/test/java/com/ridebatching/geo/BearingTest.java
[late-test]: ../src/test/java/com/ridebatching/trip/LateBookingInsertionIntegrationTest.java
[insertion-race]: ../src/test/java/com/ridebatching/trip/TripMutationConcurrencyIntegrationTest.java
[create-test]: ../src/test/java/com/ridebatching/booking/BookingCreationIntegrationTest.java
[read-test]: ../src/test/java/com/ridebatching/booking/BookingRetrievalIntegrationTest.java
[insertion-test]: ../src/test/java/com/ridebatching/trip/RouteInsertionPlannerTest.java
[security-test]: ../src/test/java/com/ridebatching/auth/SecurityIntegrationTest.java
[ownership-test]: ../src/test/java/com/ridebatching/auth/SecurityMethodIntegrationTest.java
[jwt-filter-test]: ../src/test/java/com/ridebatching/auth/JwtAuthenticationFilterTest.java
[jwt-test]: ../src/test/java/com/ridebatching/auth/JwtServiceTest.java
[rollback-test]: ../src/test/java/com/ridebatching/batching/BatchingRollbackIntegrationTest.java
[batch-race]: ../src/test/java/com/ridebatching/batching/ConcurrentBatchingIntegrationTest.java
[db-invariants]: ../src/test/java/com/ridebatching/DatabaseInvariantRegressionIntegrationTest.java
[metrics-test]: ../src/test/java/com/ridebatching/observability/ObservabilityIntegrationTest.java
[cache-test]: ../src/test/java/com/ridebatching/zone/ServiceabilityCacheIntegrationTest.java
[errors-test]: ../src/test/java/com/ridebatching/api/GlobalExceptionHandlerTest.java
[demo-test]: ../src/test/java/com/ridebatching/demo/ReviewerDemoTest.java
[demo]: demo-walkthrough.md#live-explanation-about-eight-minutes
