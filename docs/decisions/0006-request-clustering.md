# ADR 006: Leader-Based Direction Clustering

Status: Implemented in transactional batch formation, with unit and PostgreSQL integration tests.

Clustering reduces the routes worth comparing; it does not approve sharing. The [runtime pipeline](../batching-strategy.md) owns collection eligibility, and the planner still enforces every rider's hard constraints.

## Decision and rationale

Partition bounded eligible claims by source UTC window, group each window by resolved pickup-zone ID, sort zones by ID and bookings by creation time/UUID, then add each booking to the first cluster whose leader bearing is within the configured angle. The first booking stays the leader. Pickups without an active zone remain pending and are omitted from grouping.

This is a cheap geographic prefilter before expensive route evaluation. Leader comparison is inspectable and avoids transitive chaining through many slightly different headings. It is not a detour constraint: two members may each lie within 45 degrees of a leader while being 90 degrees apart from each other.

## Trade-offs and integration boundary

Greedy membership depends on leader choice and does not optimize grouping globally. Zone membership is recomputed and can become stale through caching. A removed zone yields a pending request and a warning, without automatic cancellation or escalation.

The default claim limit is 100 per worker/cycle. Windows close normally; a deadline within the configured five-minute guard enables early processing. Each resulting cluster still passes capacity, SLA and detour evaluation. Limits/concurrent workers may fragment clusters and reduce pooling; late insertion is a separate feasibility-based path. See [strategy](../batching-strategy.md) for exact eligibility/expiry rules and [complexity](../algorithm-analysis.md) for costs.
