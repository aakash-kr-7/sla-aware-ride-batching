# 5. Handle Booking Deduplication

Date: 2026-10-10

## Status

Accepted

## Context

We previously relied on a complex database index to enforce booking deduplication. As part of a recent refactor, we introduced `DeduplicationKeyGenerator` to produce a deterministic key. We needed to persist this key in the database and handle race conditions when two identical requests occur concurrently.

A naive approach would be to check for existence `exists()` before creating the row with `save()`. However, this is prone to race conditions if two threads execute `exists()` concurrently before either persists the new row.

Furthermore, if we simply attempt the save within a `@Transactional` block, a `DataIntegrityViolationException` would mark the entire transaction as rollback-only. This would prevent us from returning the existing booking to the user as a successful response, leading to a HTTP 500 error instead.

## Decision

We decided to handle the database uniqueness constraint natively and narrowly:
1. Replaced the complex multi-column unique index with a simpler index on the pre-computed `deduplication_key`.
2. Removed the `@Transactional` boundary from `BookingService.createBooking` and switched to using `BookingRepository.saveAndFlush`. This forces the constraint check immediately without relying on a transaction commit.
3. Caught the `DataIntegrityViolationException` in the service layer when `saveAndFlush` is invoked, and responded to this exception by querying and returning the existing duplicated booking.

## Consequences

- We safely resolve race conditions during concurrent identical booking creation without throwing 500 errors due to a doomed transaction.
- The `deduplication_key` column makes uniqueness explicitly visible in the data model and simpler to index in the database.
