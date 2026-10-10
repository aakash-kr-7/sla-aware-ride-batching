# ADR 005: Database-Arbitrated Booking Deduplication

Status: Implemented for active bookings created through `BookingService`.

## Decision and rationale

Use a stored deterministic key and PostgreSQL's partial unique index, not an existence check followed by insert. Two callers can both observe absence; only the database can arbitrate that race across processes. The key includes employee, normalized pickup/dropoff and UTC slot; normalization policy is owned by [assumptions](../assumptions.md).

`createBooking` has no enclosing service transaction. `saveAndFlush` uses the repository transaction, so a losing constraint violation rolls it back before the service looks up the winner. Catching an insert failure inside an already rollback-only outer transaction would not give the HTTP path a usable successful response.

## Trade-offs and scope

V4 replaces the earlier coordinate-expression index with `idx_bookings_dedup` on `deduplication_key`, excluding CANCELLED rows. There is no separate client idempotency token. Direct/legacy rows can contain null keys, so the database index alone does not deduplicate every possible write.

The catch handles any `DataIntegrityViolationException`, not just the dedup constraint. Lookup does not filter cancelled history; multiple same-key historical rows can make it ambiguous. Separate booking-save and late-insertion transactions also allow a saved booking after insertion failure. Those limits and the concurrency evidence belong in [failure modes](../failure-modes.md) and [test matrix](../test-matrix.md).
