# Failure Handling and Remaining Limits

| Failure | Protection | Recovery/limit |
| --- | --- | --- |
| DB unavailable | Aggregate health includes connectivity; transactions protect uncommitted work | No retry/circuit breaker/backup/restore workflow. Restore DB then inspect pending state before retry. Probe health does not include DB by default. |
| Crash during batching | One transaction covers trips/stops/booking associations | Rollback leaves bookings pending. Retry via admin or enabled scheduler; scheduling is off by default. |
| Duplicate request | Deterministic key, partial unique index and retrieval after duplicate insert | Same booking ID returned (201). Lookup does not filter cancelled history; rebooking lookup ambiguity remains. |
| Concurrent batching | `SKIP LOCKED` claims disjoint rows | May create fragmented batches. No later consolidation or maximum claim size. |
| Competing acceptance | Trip row lock, state guard and unique accepted-offer index | One winner; another gets state/constraint failure. Multiple open offers remain possible. |
| Failed late insertion | Insertion transaction rolls back | Original booking was saved separately and can remain pending. Post-lock refresh does not rebuild booking map. |
| Cancellation | Affected trip alone is locked/resequenced | No feasibility recheck, offer revoke or driver release; pickup-time cancellation is not blocked. |
| Stale zone cache | Five-minute TTL; annotated save/saveAll/delete/deleteAll evict locally | Other write variants, direct SQL and other instances can remain stale until expiry. |
| Invalid/expired JWT | Non-leaking 401 | No revocation list; account data reloaded each request. Filter catches downstream faults too and can mislabel them. |
| Invalid domain state | Explicit mappings for validation/missing booking/serviceability | Other argument/state exceptions are not consistently mapped; no blanket 409 guarantee. |
| Infeasible dedicated trip | Remains pending | No automatic escalation producer; manual inspection needed. |

Tests cover duplicate races, rollback, dispatch contention and late-insertion capacity. They do not establish complete fault tolerance or recovery readiness.

Flyway V8/V10 had fresh-schema blockers (wrong employee FK target, existing index name). They were corrected; V1-V7/V9 remain unchanged. If an external DB has manually repaired/applied those files already, review schema and checksums before upgrading; do not blindly run `flyway repair`.

See [observability](observability.md) and [tests](test-matrix.md).
