# Trip Mutation Boundaries

## Late insertion

After saving a booking in its own repository transaction, `BookingService` calls `BatchingService.attemptLateInsertion` in a separate transaction:

1. Load `PLANNED` trips and their bookings.
2. Choose a feasible candidate using default planning capacity.
3. Lock the selected trip with `PESSIMISTIC_WRITE`, refresh it and re-evaluate.
4. Update route/confirm booking if feasible; otherwise leave it pending.

Dispatched/in-progress trips are excluded; unrelated routes are untouched. The concurrency test verifies competing late requests cannot overfill the vehicle.

Trip refresh does not rebuild the booking map assembled before locking. Concurrent insertion/cancellation may need additional refresh work. Booking save and insertion are separate transactions, so an insertion failure may leave a durable pending booking after an API failure.

## Cancellation

One transaction loads the booking, checks supplied employee ID, cancels it and locks its trip. Its two stops are removed; others retain order and are resequenced. Empty trip becomes `CANCELLED`.

Unrelated trips are untouched. There is no feasibility recheck, offer revocation or driver release. Cancellation is idempotent and rejects `DROPPED`, but currently permits `PICKED_UP`. These are limits, not complete execution-time replanning.

See [domain model](domain-model.md), [authorization](auth-boundaries.md), [tests](test-matrix.md).
