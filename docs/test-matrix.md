# Test Coverage and Gaps

Paths below are relative to `src/test/java/com/ridebatching`. Component tests do not prove runtime wiring. Full-application integration tests share PostgreSQL 16 through `AbstractPostgresIntegrationTest`; the account JPA slice has its own container. DB/cache state is cleared between application tests.

| Scenario | Tests | Boundary/gap |
| --- | --- | --- |
| Dedup key/uniqueness | booking/DeduplicationKeyGeneratorTest, booking/BookingPersistenceIntegrationTest | Normalization and DB constraint; cancellation/rebooking lookup needs coverage |
| Concurrent double submit | booking/BookingConcurrencyIntegrationTest | Ten callers produce one row and one ID |
| Serviceability | zone/ServiceabilityServiceTest, zone/ServiceableZoneIntegrationTest, booking/api/BookingControllerTest | Radius/overlap/ties, DB bounds, HTTP rejection; no dropoff check |
| Window | batching/BatchingWindowTest | Standalone boundary/selection; runtime integration absent |
| Clustering | batching/RequestClustererTest, geo/BearingTest | Zone/leader bearing and wrap-around; runtime integration absent |
| Capacity/split | batching/constraint/CapacityConstraintTest, batching/BatchPlannerAcceptanceTest | Exact/overflow and five riders/four seats |
| SLA | batching/constraint/PickupSlaConstraintTest, batching/BatchPlannerAcceptanceTest | Exact boundary, one second late, insertion rejection, expired solo stays pending; no driver approach |
| Configured SLA | booking/BookingServiceTest | Deadline respects non-default duration |
| Detour | batching/constraint/DetourConstraintTest, batching/evaluator/TripFeasibilityEvaluatorTest | Relative/absolute thresholds and harmed rider |
| Routes | trip/TripTest, trip/RouteInsertionPlannerTest, batching/RoutePlannerInvariantTest | Order, uniqueness, occupancy and SLA; generated test does not assert detour |
| Driver selection | driver/DriverEligibilityPolicyTest, driver/DriverProximityRankerTest, dispatch/DispatchIntegrationTest | Break/offline/assigned, distance ranking, cool-off |
| Acceptance race | dispatch/DispatchIntegrationTest, dispatch/DriverOfferPersistenceIntegrationTest | One winner/trip; same driver across different trips not covered |
| Late insertion | trip/LateBookingInsertionIntegrationTest, trip/TripMutationConcurrencyIntegrationTest | Near/far candidates and capacity race; insertion/cancel race not covered |
| Cancellation | booking/BookingCancellationIntegrationTest, booking/BookingServiceTest | Affected trip isolation; no execution-time feasibility/driver release |
| Authorization | auth/SecurityIntegrationTest, auth/SecurityMethodIntegrationTest | Login, ownership, admin/approver boundaries |
| Local bootstrap | dev/LocalDevBootstrapTest | Local profile and opt-in flag, required credentials, encoded account creation |
| Rollback/concurrency | batching/BatchingRollbackIntegrationTest, batching/ConcurrentBatchingIntegrationTest | Failure rollback, retry and disjoint claims |
| Cache/monitoring | zone/ServiceabilityCacheIntegrationTest, observability/ObservabilityIntegrationTest | Cached reads/eviction, probes, Prometheus |
| Fresh schema | DatabaseIntegrationTest, RideBatchingApplicationTests, repository integration tests | Flyway and JPA validation on PostgreSQL |

Run `./mvnw test` (Windows: `./mvnw.cmd test`); `clean verify` also packages the app. Docker is required; no tests are disabled to accommodate a missing daemon.

JMH is performance evidence, not a correctness test/live capacity guarantee: [benchmark notes](benchmarks/batching.md).
