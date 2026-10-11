# Requirements Traceability

Verified against the supplied MoveInSync Backend Case Study 2026, Case Study 7: On-Demand Ride Batching, pages 1-2. R rows map to sections I-IV; P rows map to the eight plus-point criteria. R/P/T labels are repository navigation IDs, not official numbering.

## Problem and coverage rules

Employees submit ad-hoc pickup/dropoff requests. Sharing should reduce unnecessary cabs without trading away pickup promises, seat limits or rider detours. Nearby pickup and direction are grouping hints; feasibility takes precedence. Drivers must be eligible, and one cancellation must not reshuffle unrelated trips. The source's 15-minute window, 20-minute SLA and four-seater example are illustrative; [assumptions](assumptions.md) records the chosen runtime policies.

**Implemented** means the stated scope has code and behavioural evidence. **Partial** identifies missing integration, execution handling or validation. Neither label claims actual road-arrival guarantees. Tests below link directly to source; T IDs connect to the more detailed [test matrix](test-matrix.md). The source also requires a short video or screenshots with sample API calls as a separate submission deliverable; those media are not included here.

## Core requirements

| ID / requirement | Implementation and status | Automated evidence | Boundary |
| --- | --- | --- | --- |
| R01 Serviceable pickup | **Implemented at creation.** [BookingService][booking] checks [ServiceabilityService][zones] before insert; active circles, radius inclusion and deterministic overlap selection. | T01: [ServiceabilityServiceTest][zone-test], [ServiceableZoneIntegrationTest][zone-db-test], [BookingControllerTest][booking-http-test]. | Pickup only; no dropoff policy or batching-time revalidation. Zone-cache freshness has limits. |
| R02 One logical booking after double-submit | **Implemented for active service-created requests.** [DeduplicationKeyGenerator][dedup] normalizes employee/endpoints/slot; [BookingRepository][booking-repo] arbitrates inserts against [V4][v4]'s partial unique index; [V11][v11] disallows null keys. | T02: [DeduplicationKeyGeneratorTest][dedup-test], [BookingPersistenceIntegrationTest][booking-db-test], [BookingConcurrencyIntegrationTest][duplicate-race], [BookingMutationRegressionIntegrationTest][booking-races]. | Active identity uses employee, normalized endpoints and UTC slot. No client idempotency token; direct writers must use canonical keys. |
| R03 Capacity | **Implemented computed constraint and driver checks.** [TripFeasibilityEvaluator][evaluator] computes peak occupancy; [CapacityConstraint][capacity] checks planning seats; [DispatchService][dispatch] checks actual driver capacity at selection/acceptance. | T03: [CapacityConstraintTest][capacity-test], [BatchPlannerAcceptanceTest][planner-test], [DispatchMutationRegressionIntegrationTest][dispatch-races]. | One booking is one rider. Capacity bounds simultaneous occupancy, not total route stops. Actual driver capacity is checked separately. |
| R04 Pickup SLA; split or escalate | **Partial workflow.** [PickupSlaConstraint][sla] checks every pickup deadline; [BatchPlanner][planner] checks a solo route when sharing fails and leaves infeasible solo requests pending. | T04: [PickupSlaConstraintTest][sla-test], [BatchPlannerAcceptanceTest][planner-test], [BookingServiceTest][booking-test]. | Sharing can split into feasible solo trips. An infeasible solo request stays pending; no automatic escalation or driver-approach estimate. |
| R05 Rider detour | **Implemented estimated policy.** [DetourConstraint][detour] applies both relative and absolute extra-time bounds to all riders in the candidate route. | T05: [DetourConstraintTest][detour-test], [TripFeasibilityEvaluatorTest][evaluator-test], [RoutePlannerInvariantTest][invariant-test]. | Relative and absolute detour policies use the simplified estimator. No road-time calibration or complete alternative-route search. |
| R06 Eligible nearby driver | **Implemented service behaviour.** [DriverEligibilityPolicy][eligibility] excludes offline/break/active-trip drivers; [DriverProximityRanker][ranker] sorts eligible drivers by Haversine distance then ID; [DispatchService][dispatch] persists offers. | T06: [DriverEligibilityPolicyTest][eligibility-test], [DriverProximityRankerTest][ranker-test], [DispatchIntegrationTest][dispatch-test]. | Break/offline/active-trip exclusion and nearest offers exist as services. No driver HTTP API, notification transport or batch-to-dispatch integration. |
| R07 Rejection cool-off and next offer | **Partial workflow.** [RejectionCoolOffPolicy][cooloff] uses rejection-time `updatedAt`; [DispatchService][dispatch] skips recent rejections for the same trip on an explicit later dispatch call. | T07: [DispatchIntegrationTest][dispatch-test], [DispatchMutationRegressionIntegrationTest][dispatch-races] prove exclusion, old-offer rejection timing and exact expiry. | A subsequent explicit dispatch call skips recent rejections. No automatic re-offer workflow; five minutes is chosen policy. |
| R08 Cancellation isolation | **Partial execution handling.** [BookingService][booking] locks booking/trip; [Trip][trip] removes two stops and resequences. Empty trips cancel and [DispatchService][dispatch] releases matching assignments/outstanding offers. | T08: [BookingCancellationIntegrationTest][cancel-test], [BookingMutationRegressionIntegrationTest][booking-races], [TripTest][trip-test]. | Stop removal/resequencing affects one trip. No fresh optimization or execution-time feasibility check; PENDING/CONFIRMED bookings may cancel. |
| R09 Window plus zone/direction clustering | **Partial components; runtime integration absent.** [BatchingWindow][window] supplies creation's UTC dedup slot; [RequestClusterer][cluster] groups standalone inputs. [BatchingService][batching] claims all pending rows without slot/zone/direction filters. | T09: [BatchingWindowTest][window-test], [RequestClustererTest][cluster-test], [BearingTest][bearing-test]. | Core integration gap: no runtime time-window/zone/direction filter or claim-size bound. Standalone component tests do not prove orchestration. |
| R10 Late add when all constraints still fit | **Implemented for planned trips.** [BatchingService][batching] locks booking then chosen trip, reloads current riders and re-evaluates; [BookingService][booking] keeps creation/insertion atomic. | T10: [LateBookingInsertionIntegrationTest][late-test], [TripMutationConcurrencyIntegrationTest][insertion-race], [BookingMutationRegressionIntegrationTest][booking-races]. | Planned trips only. Locks recheck every current rider and the latest trip state; no insertion into dispatched/executing routes. |
| R11 Booking data and progress | **Partial lifecycle.** [Booking][booking-entity], [BookingController][booking-controller], [Trip][trip] persist and expose employee/endpoints/slot/deadline/status/association; service flows reach PENDING, CONFIRMED, CANCELLED. | T11: [BookingCreationIntegrationTest][create-test], [BookingRetrievalIntegrationTest][read-test], [TripTest][trip-test], [RouteInsertionPlannerTest][insertion-test]. | Server creation time stands in for requested time. PICKED_UP/DROPPED exist as enum values, without an execution workflow or trip-read HTTP API. |

## Evaluation criteria

| ID / criterion | Implementation / evidence | Boundary |
| --- | --- | --- |
| P01 Authentication and roles | [SecurityConfig][security], [JwtAuthenticationFilter][jwt-filter], [JwtService][jwt], [AuthController][auth]; T12: [SecurityIntegrationTest][security-test], [SecurityMethodIntegrationTest][ownership-test], [JwtAuthenticationFilterTest][jwt-filter-test], [JwtServiceTest][jwt-test]. | Current account controls roles/ownership. Public operational endpoints are a declared policy. |
| P02 Algorithm cost | [RouteInsertionPlanner][insertion], [BatchPlanner][planner], [RequestClusterer][cluster]; [analysis](algorithm-analysis.md), [JMH source][benchmark] and [dated synthetic measurements](benchmarks/batching.md). Behaviour: [RouteInsertionPlannerTest][insertion-test], [RoutePlannerInvariantTest][invariant-test]. | Complexity and dated synthetic measurements are inspectable; no service latency/load guarantee. |
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
