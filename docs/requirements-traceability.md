# Requirements, implementation and evidence

Verified against the supplied MoveInSync Backend Case Study 2026, Case Study 7: On-Demand Ride Batching, pages 1-2. R rows map to sections I-IV; P rows map to the eight plus-point criteria. R/P/T labels are repository navigation IDs, not official numbering.

## Problem and coverage rules

Employees submit ad-hoc pickup/dropoff requests. Sharing should reduce unnecessary cabs without trading away pickup promises, seat limits or rider detours. Nearby pickup and direction are grouping hints; feasibility takes precedence. Drivers must be eligible, and one cancellation must not reshuffle unrelated trips. The source's 15-minute window, 20-minute SLA and four-seater example are illustrative; [assumptions](assumptions.md) records the chosen runtime policies.

**Implemented** means the stated scope has code and behavioural evidence. **Partial** identifies missing integration, execution handling or validation. Neither label claims actual road-arrival guarantees. Tests below link directly to source; T IDs connect to the more detailed [test matrix](test-matrix.md). The source also requires a short video or screenshots with sample API calls as a separate submission deliverable; those media are not included here.

For a fast review, run the [fresh-clone workflow](local-development.md), then use the tables to find the corresponding code and tests. The [recording guide](reviewer-guide.md#what-to-capture-separately) identifies useful actual responses to submit separately.

## Core requirements

| ID / requirement | Implementation and status | Automated evidence | Boundary |
| --- | --- | --- | --- |
| R01 Serviceable pickup | **Implemented at creation and batch grouping.** [BookingService][booking] checks [ServiceabilityService][zones] before insert; [RequestClusterer][cluster] omits claims with no active zone. Active circles, radius inclusion and deterministic overlap selection. | T01: [ServiceabilityServiceTest][zone-test], [ServiceableZoneIntegrationTest][zone-db-test], [BookingControllerTest][booking-http-test], [WindowedBatchingIntegrationTest](../src/test/java/com/ridebatching/batching/WindowedBatchingIntegrationTest.java). | Pickup only; no dropoff policy. Revalidation uses cached active zones with documented freshness limits. |
| R02 One logical booking after double-submit | **Implemented for active service-created requests.** [DeduplicationKeyGenerator][dedup] normalizes employee/endpoints/slot; [BookingRepository][booking-repo] arbitrates inserts against [V4][v4]'s partial unique index; [V11][v11] disallows null keys. | T02: [DeduplicationKeyGeneratorTest][dedup-test], [BookingPersistenceIntegrationTest][booking-db-test], [BookingConcurrencyIntegrationTest][duplicate-race], [BookingMutationRegressionIntegrationTest][booking-races]. | Active identity uses employee, normalized endpoints and UTC slot. No client idempotency token; direct writers must use canonical keys. |
| R03 Capacity | **Implemented computed constraint and driver checks.** [TripFeasibilityEvaluator][evaluator] computes peak occupancy; [CapacityConstraint][capacity] checks planning seats; [DispatchService][dispatch] checks actual driver capacity at selection/acceptance; [TripRouteTiming](../src/main/java/com/ridebatching/trip/TripRouteTiming.java) rechecks it on accepted-trip cancellation. | T03: [CapacityConstraintTest][capacity-test], [BatchPlannerAcceptanceTest][planner-test], [DispatchMutationRegressionIntegrationTest][dispatch-races]. | One booking is one rider. Capacity bounds simultaneous occupancy, not total route stops. Actual driver capacity is checked separately. |
| R04 Pickup SLA; split or escalate | **Implemented estimated planning and persisted missed-promise policy.** [PickupSlaConstraint][sla] checks every pickup; [BatchPlanner][planner] searches insertion alternatives for the cheapest feasible route before falling back to solo trips. [TripAssignmentValidator](../src/main/java/com/ridebatching/dispatch/TripAssignmentValidator.java) repeats SLA/detour checks at offering and new acceptance, including current driver approach. [BatchingService][batching] separately claims expired pending requests and [BookingSlaEscalations](../src/main/java/com/ridebatching/escalation/BookingSlaEscalations.java) persists one OPEN issue per booking/reason, protected by [V14](../src/main/resources/db/migration/V14__unique_open_sla_issues.sql). | T04: [FeasibleInsertionRegressionTest](../src/test/java/com/ridebatching/batching/FeasibleInsertionRegressionTest.java), [AssignmentTimingHttpIntegrationTest](../src/test/java/com/ridebatching/dispatch/api/AssignmentTimingHttpIntegrationTest.java), [PickupSlaConstraintTest][sla-test], [BatchPlannerAcceptanceTest][planner-test], [BookingSlaEscalationIntegrationTest](../src/test/java/com/ridebatching/batching/BookingSlaEscalationIntegrationTest.java), [BookingLifecycleHttpIntegrationTest](../src/test/java/com/ridebatching/booking/api/BookingLifecycleHttpIntegrationTest.java). [Reproduction](booking-lifecycle.md#evidence). | Unassigned solo pickup optimistically equals cycle time; expired promises stay pending/escalated. New assignment checks current-time approach and rolls back infeasible acceptance; no actual arrival guarantee. [ETA/search reproduction](planner-and-eta.md#verification). |
| R05 Rider detour | **Implemented estimated policy.** [DetourConstraint][detour] applies both relative and absolute extra-time bounds to all riders in insertion, offered assignments and pre-execution route changes. | T05: [FeasibleInsertionRegressionTest](../src/test/java/com/ridebatching/batching/FeasibleInsertionRegressionTest.java), [AssignmentTimingHttpIntegrationTest](../src/test/java/com/ridebatching/dispatch/api/AssignmentTimingHttpIntegrationTest.java), [DetourConstraintTest][detour-test], [TripFeasibilityEvaluatorTest][evaluator-test], [RoutePlannerInvariantTest][invariant-test]. | Relative and absolute detour policies use the simplified estimator. All new-rider insertion positions are explored without reordering existing stops; no global route optimum or road-time calibration. |
| R06 Eligible nearby driver | **Implemented connected workflow with admin simulation.** [DriverEligibilityPolicy][eligibility], [DriverProximityRanker][ranker] and [DispatchService][dispatch] select eligible nearby drivers; [BatchingService][batching] attempts initial offers automatically. [DispatchAdminController](../src/main/java/com/ridebatching/dispatch/api/DispatchAdminController.java) exposes stored identity only. | T06: [AssignmentTimingHttpIntegrationTest](../src/test/java/com/ridebatching/dispatch/api/AssignmentTimingHttpIntegrationTest.java), [DriverEligibilityPolicyTest][eligibility-test], [DriverProximityRankerTest][ranker-test], [DispatchIntegrationTest][dispatch-test], [DispatchWorkflowHttpIntegrationTest](../src/test/java/com/ridebatching/dispatch/api/DispatchWorkflowHttpIntegrationTest.java). Reproduce with [workflow test command](dispatch-workflow.md#verification). | No driver login or notification transport; offers are provisional until acceptance. Current driver approach, capacity/eligibility and full route constraints are rechecked under acceptance locks. Pending-offer estimates are planning estimates; [ETA policy](planner-and-eta.md) documents rollback and retry. |
| R07 Rejection cool-off and next offer | **Implemented bounded workflow.** [RejectionCoolOffPolicy][cooloff] measures rejection time; [DispatchService][dispatch] automatically advances rejection to an untried driver. [DispatchExpiryJob](../src/main/java/com/ridebatching/dispatch/DispatchExpiryJob.java) advances deadline-expired offers. | T07: [DispatchLifecycleIntegrationTest](../src/test/java/com/ridebatching/dispatch/DispatchLifecycleIntegrationTest.java), [DispatchWorkflowHttpIntegrationTest](../src/test/java/com/ridebatching/dispatch/api/DispatchWorkflowHttpIntegrationTest.java), [DispatchMutationRegressionIntegrationTest][dispatch-races]. [HTTP workflow / reproduction](dispatch-workflow.md). | Five-minute same-trip cool-off plus permanent no-repeat per trip; three offers maximum by default. No eligible/untried driver or attempt exhaustion leaves trip PLANNED, without automatic escalation. Empty rounds require explicit Advance after availability changes. |
| R08 Cancellation isolation | **Implemented before execution; execution-time cancellation unsupported.** [BookingService][booking] locks booking/trip; [Trip][trip] removes two stops/resequences; [TripRouteTiming](../src/main/java/com/ridebatching/trip/TripRouteTiming.java) recomputes arrivals, rechecks capacity/detour and flags remaining pickup risk. Accepted trips include current driver approach. Empty trips cancel/release assignments. | T08: [AssignmentTimingHttpIntegrationTest](../src/test/java/com/ridebatching/dispatch/api/AssignmentTimingHttpIntegrationTest.java), [BookingCancellationIntegrationTest][cancel-test], [BookingMutationRegressionIntegrationTest][booking-races], [BookingLifecycleHttpIntegrationTest](../src/test/java/com/ridebatching/booking/api/BookingLifecycleHttpIntegrationTest.java), [DispatchWorkflowHttpIntegrationTest](../src/test/java/com/ridebatching/dispatch/api/DispatchWorkflowHttpIntegrationTest.java). Tests compare unrelated routes, estimates and timestamps, stable unresolved issues, and forced HTTP mutation races. [Reproduction](booking-lifecycle.md#evidence). | Cancellation closes for the entire trip at its first pickup, even for still-CONFIRMED riders. Remaining stop order is preserved; no fresh optimization or calibrated road ETA. |
| R09 Window plus zone/direction clustering | **Implemented in batch formation.** [BatchClaimPolicy](../src/main/java/com/ridebatching/batching/BatchClaimPolicy.java) defines fixed UTC windows, urgent eligibility and claim bounds. [BookingRepository][booking-repo] claims eligible rows; [BatchingService][batching] partitions source windows, invokes [RequestClusterer][cluster], then plans each cluster. | T09: [WindowedBatchingIntegrationTest](../src/test/java/com/ridebatching/batching/WindowedBatchingIntegrationTest.java), [BatchClaimIntegrationTest](../src/test/java/com/ridebatching/batching/BatchClaimIntegrationTest.java), [ConcurrentBatchingIntegrationTest][batch-race], [BatchingAdminControllerIntegrationTest](../src/test/java/com/ridebatching/batching/api/BatchingAdminControllerIntegrationTest.java), [BatchingWindowTest][window-test], [RequestClustererTest][cluster-test]. | Default 100 claims per worker/cycle; open windows release urgent bookings within five minutes of deadline. Expired requests remain pending/excluded from planning claims, with a separately bounded escalation pass. Limits/workers may fragment clusters. Late insertion is a separate path; database scans and active-zone count are not bounded by the claim limit. |
| R10 Late add when all constraints still fit | **Implemented for planned trips.** [BatchingService][batching] locks booking then chosen trip, reloads current riders and re-evaluates; [BookingService][booking] keeps creation/insertion atomic. | T10: [LateBookingInsertionIntegrationTest][late-test], [TripMutationConcurrencyIntegrationTest][insertion-race], [BookingMutationRegressionIntegrationTest][booking-races]. | Planned trips only. Locks recheck every current rider and the latest trip state; no insertion into dispatched/executing routes. |
| R11 Booking data and progress | **Implemented minimum lifecycle via authenticated admin simulation.** [Booking][booking-entity], [BookingController][booking-controller], [BookingExecutionService](../src/main/java/com/ridebatching/booking/BookingExecutionService.java) and [Trip][trip] persist/expose the requested booking data and PENDING -> CONFIRMED -> PICKED_UP -> DROPPED progression. | T11: [BookingCreationIntegrationTest][create-test], [BookingRetrievalIntegrationTest][read-test], [BookingTransitionTest](../src/test/java/com/ridebatching/booking/BookingTransitionTest.java), [BookingLifecycleHttpIntegrationTest](../src/test/java/com/ridebatching/booking/api/BookingLifecycleHttpIntegrationTest.java). Real HTTP creation, planning, acceptance, ordered stops, completion/driver release, invalid states and role denial. [Reproduction](booking-lifecycle.md#evidence). | CONFIRMED is feasible route association, separate from driver acceptance. Execution requires the accepted driver and next unfinished stop. Server creation time supplies requested time; no driver login or actual arrival timestamps. |

Authenticated [trip inspection](trip-inspection.md) makes R03/R04/R05/R06/R08 results inspectable: status/driver association, grouped IDs, ordered stops, current planning capacity/peak occupancy, stored pickup estimates/deadlines and optional hypothetical rejection reasons. Implementation: [DispatchView](../src/main/java/com/ridebatching/dispatch/DispatchView.java), [DispatchService][dispatch], [TripInsertionInspector](../src/main/java/com/ridebatching/dispatch/TripInsertionInspector.java). Behavioural evidence: [TripInspectionHttpIntegrationTest](../src/test/java/com/ridebatching/dispatch/api/TripInspectionHttpIntegrationTest.java). The comparison reuses the evaluator and checks current route constraints; it does not reconstruct historical grouping decisions or apply window/zone/direction gates. [Reproduction](trip-inspection.md#commands-and-evidence).

## Public reproduction paths

`python scripts/local-review.py` runs the normal HTTP entry points against explicit local reference fixtures. It stores real responses under ignored `target/local-review/` and checks the following outcomes. The fixture profile releases fresh requests immediately; it does not weaken capacity, SLA, detour, ownership or acceptance checks.

| Requirement | Captured evidence / explanation |
| --- | --- |
| R01 Pickup admission, R02 logical identity | `03-input-contracts.json`: useful 422 zone error, duplicate returns the same ID, employee list still contains one booking |
| R03 Capacity, R04 estimated SLA | `04-four-plus-one.json`: two routes, occupancy four/one, every saved pickup meets its deadline. `05-sharing-reasons.json`: all 45 alternative insertions fail capacity or SLA checks |
| R06 Eligibility, R07 rejection/cool-off | `06-next-driver.json`: nearest four-seat driver REJECTED, eligible backup OFFERED. `07-accepted.json`: matching accepted assignment and approach-aware estimates |
| R08 Cancellation isolation | `08-cancellation-isolation.json`: affected route becomes three riders/six stops; unrelated response is identical |
| P01 Authentication, P06 monitoring | Script logs in through real JWT authentication as employee/admin; `01-health.json` and `09-metrics.json` capture health and selected process counters |

The script does not separately demonstrate every detour, source-window/zone/direction boundary, escalation, late insertion, execution or expiry case. Those have focused test commands in the linked policy documents and [test matrix](test-matrix.md). The sharing comparison is current-time hypothetical evidence, not a stored explanation of the original grouping decision. The temporary same-trip cool-off and stronger permanent no-repeat rule are distinguished in [dispatch policy](dispatch-workflow.md#policies-and-outcomes).

Local setup itself is implemented by [LocalFixtures](../src/main/java/com/ridebatching/dev/LocalFixtures.java), [profile configuration](../src/main/resources/application-local-fixtures.yml) and [isolated Compose](../compose.local.yml). [Integration](../src/test/java/com/ridebatching/dev/LocalFixturesIntegrationTest.java) and [isolation](../src/test/java/com/ridebatching/dev/LocalFixturesIsolationTest.java) tests cover its safe loading boundaries. [Exact fresh-clone commands](local-development.md#a-fresh-clone-with-usable-accounts) require no ignored personal files or SQL editing.

## Evaluation criteria

| ID / criterion | Implementation / evidence | Boundary |
| --- | --- | --- |
| P01 Authentication and roles | [SecurityConfig][security], [JwtAuthenticationFilter][jwt-filter], [JwtService][jwt], [AuthController][auth]; T12: [SecurityIntegrationTest][security-test], [SecurityMethodIntegrationTest][ownership-test], [JwtAuthenticationFilterTest][jwt-filter-test], [JwtServiceTest][jwt-test]. | Current account controls roles/ownership. Public operational endpoints are a declared policy. |
| P02 Algorithm cost | [RouteInsertionPlanner][insertion], [BatchPlanner][planner], [RequestClusterer][cluster]; [analysis](algorithm-analysis.md), [JMH source][benchmark] and [dated synthetic measurements](benchmarks/batching.md). Behaviour: [FeasibleInsertionRegressionTest](../src/test/java/com/ridebatching/batching/FeasibleInsertionRegressionTest.java), [RouteInsertionPlannerTest][insertion-test], [RoutePlannerInvariantTest][invariant-test]. | Complexity and dated synthetic measurements are inspectable; no service latency/load guarantee. |
| P03 Failure/rollback/recovery | Transactions in [BatchingService][batching], [BookingService][booking], [DispatchService][dispatch] and DB constraints in [V11][v11]. T13: [BatchingRollbackIntegrationTest][rollback-test], [ConcurrentBatchingIntegrationTest][batch-race], [DatabaseInvariantRegressionIntegrationTest][db-invariants], [DispatchMutationRegressionIntegrationTest][dispatch-races]. | Rollback and concrete races are tested. No backup/restore, crash-recovery or failover validation. |
| P04 OOP and layering | [Architecture](architecture.md), [Trip][trip] aggregate, [BookingController][booking-controller]/[BookingService][booking]/[BookingRepository][booking-repo]. Domain evidence: [TripTest][trip-test], [BookingServiceTest][booking-test]. | Feature packages with controller/service/repository layers. Escalation reading currently returns entities directly. |
| P05 Trade-offs | [Assumptions](assumptions.md), [strategy](batching-strategy.md), [dedup decision](decisions/0005-handle-booking-deduplication.md), [clustering decision](decisions/0006-request-clustering.md), [cache decision](decisions/007-zone-caching.md). Their runtime choices link to R02/R05/R09/P07 and corresponding tests above/below. | Policies and search compromises are explicit. No global optimum or road-ETA accuracy claim. |
| P06 Monitoring/logging | [Application exposure settings][settings], Micrometer instrumentation in [BookingService][booking]/[BatchingService][batching]/[TripFeasibilityEvaluator][evaluator]/[DispatchService][dispatch], [CorrelationIdFilter][correlation]. T14: [ObservabilityIntegrationTest][metrics-test]. | Health and public Prometheus HTTP access are tested. No dedicated correlation-header/MDC test, dashboards or alerts; counters are not transactional success totals. |
| P07 Caching and staleness | [CacheConfig][cache], [ServiceableZoneRepository][zone-repo]; T15: [ServiceabilityCacheIntegrationTest][cache-test] covers cached empty lists, write eviction, concurrent delete/reader and rollback cases. | Local TTL/after-commit eviction does not prove cross-instance/direct-SQL freshness. TTL timing and all bulk variants are not fully tested. |
| P08 Errors and exceptions | [GlobalExceptionHandler][errors], [JwtAuthenticationFilter][jwt-filter]. T12: [GlobalExceptionHandlerTest][errors-test], [BookingControllerTest][booking-http-test], [SecurityMethodIntegrationTest][ownership-test], [JwtAuthenticationFilterTest][jwt-filter-test]. | Explicit validation/auth/domain/state contracts; login failure is plain text and unexpected internal failures remain server errors. |


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
