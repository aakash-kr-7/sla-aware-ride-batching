# Business Edge-Case Test Matrix

This matrix maps business edge cases to their corresponding automated tests.

| Scenario | Invariant | Test type | Existing test | Gap |
| --- | --- | --- | --- | --- |
| Duplicate tap | A single user cannot create multiple identical bookings in a short window. | Unit/Integration | `TripFeasibilityEvaluatorTest.shouldRejectWhenStructuralCheckFails_DuplicatePickup`, `BookingPersistenceIntegrationTest` | No gap |
| Concurrent duplicate | A user rapidly tapping 'book' must not result in multiple database rows. | Integration | `ConcurrentBatchingIntegrationTest.shouldHandleConcurrentBatchingAttemptsWithoutDuplicates` | No gap |
| Serviceable-zone boundary | Pickups/dropoffs outside geofenced zones must be rejected immediately. | Unit/Integration | `ServiceableZoneIntegrationTest` | No gap |
| Batching-window boundary | Bookings must only be batched with others in the same time window. | Unit | `BatchingWindowTest` | No gap |
| Direction 0°/360° wrap-around | Trajectories straddling North (359° and 1°) must cluster together correctly. | Unit | `BearingTest` | No gap |
| Capacity exact/full/overflow | A trip cannot exceed physical vehicle seats (e.g. 5 passengers in a 4-seater). | Unit/Integration | `BatchPlannerAcceptanceTest.shouldSplitFiveCompatibleRequestsIntoTwoTripsDueToCapacity`, `TripMutationConcurrencyIntegrationTest` | No gap |
| SLA exact boundary | A detour or late insertion must not cause an existing rider to miss their SLA by even 1 ms. | Unit | `PickupSlaConstraintTest` | No gap |
| Detour boundary | Detours cannot exceed the absolute and relative max bounds for any passenger. | Unit | `DetourConstraintTest` | No gap |
| Five requests/four seats | Exactly 5 compatible requests must split into exactly 2 trips. | Unit | `BatchPlannerAcceptanceTest` | No gap |
| Driver break | A driver on break cannot be offered a trip. | Unit/Integration | `DriverEligibilityPolicyTest`, `DriverPersistenceIntegrationTest` | No gap |
| Driver rejection cooldown | A driver who rejected an offer must not receive the same trip again inside the cool-off period. | Integration | `DispatchIntegrationTest.rejectedDriverExcludedInsideCoolOff` | No gap |
| Competing driver acceptances | If multiple drivers are offered trips simultaneously, only one driver can claim a given trip. | Integration | `DispatchIntegrationTest.concurrentAcceptanceOnlyAllowsOneWinner` | No gap |
| Late insertion | New bookings can be inserted into an ongoing trip without violating SLA/Capacity. | Integration | `LateBookingInsertionIntegrationTest` | No gap |
| Cancellation isolation | Cancelling a booking must not cancel the entire trip for other riders. | Integration | `BookingCancellationIntegrationTest` | No gap |
| Concurrent trip mutation | Multiple late insertions processed simultaneously must not overfill a trip. | Integration | `TripMutationConcurrencyIntegrationTest` | No gap |
| Authorization ownership | A user cannot view or cancel another user's booking. | Integration | `SecurityMethodIntegrationTest` | No gap |
| Transaction rollback | A failed batching operation must release all pessimistic locks. | Integration | `BatchingRollbackIntegrationTest` | No gap |
