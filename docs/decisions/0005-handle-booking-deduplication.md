# ADR 005: Database-Arbitrated Booking Deduplication

Status: Implemented for active bookings created through `BookingService`.

The decision answers a concrete race: two callers can submit the same journey before either sees the other's write. [API identity](../api-design.md#booking-routes) defines the resulting contract; [concurrency evidence](../failure-modes.md#transaction-ownership) covers competing submissions.

## Decision and rationale

Use a stored deterministic key and PostgreSQL's partial unique index, not an existence check followed by insert. Two callers can both observe absence; only the database can arbitrate that race across processes. The key includes employee, normalized pickup/dropoff and UTC slot; normalization policy is owned by [assumptions](../assumptions.md).

`createBooking` owns one transaction including late insertion. A native `INSERT ... ON CONFLICT ... DO NOTHING` targets the active-key partial index. A losing insert does not abort the transaction; it reads only non-cancelled history and returns the active booking. Other constraint failures propagate and roll back. This avoids catching a uniqueness exception inside a rollback-only transaction.

## Trade-offs and scope

V4 replaces the earlier coordinate-expression index with `idx_bookings_dedup` on `deduplication_key`, excluding CANCELLED rows. There is no separate client idempotency token. V11 requires non-null keys; a legacy database with null keys needs explicit identity repair before upgrade. Direct writers must still use the canonical key normalization.

A failure in late insertion rolls back creation too. PostgreSQL regressions cover concurrent duplicate creation, repeated cancel/rebook history and injected insertion failure; see [failure modes](../failure-modes.md).
