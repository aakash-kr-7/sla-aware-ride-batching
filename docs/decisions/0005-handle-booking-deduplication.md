# ADR 005: Database-Arbitrated Booking Deduplication

Status: Implemented for active bookings created through `BookingService`.

## Decision and rationale

Use a stored deterministic key and PostgreSQL's partial unique index, not an existence check followed by insert. Two callers can both observe absence; only the database can arbitrate that race across processes. The key includes employee, normalized pickup/dropoff and UTC slot; normalization policy is owned by [assumptions](../assumptions.md).

`createBooking` owns one transaction including late insertion. A native `INSERT ... ON CONFLICT ... DO NOTHING` targets the active-key partial index. A losing insert does not abort the transaction; it reads only non-cancelled history and returns the active booking. Other constraint failures propagate and roll back. This avoids catching a uniqueness exception inside a rollback-only transaction.

## Trade-offs and scope

V4 replaces the earlier coordinate-expression index with `idx_bookings_dedup` on `deduplication_key`, excluding CANCELLED rows. There is no separate client idempotency token. Direct/legacy rows can contain null keys, so the database index alone does not deduplicate every possible write.

A failure in late insertion rolls back creation too. PostgreSQL regressions cover concurrent duplicate creation, repeated cancel/rebook history and injected insertion failure; see [failure modes](../failure-modes.md).
