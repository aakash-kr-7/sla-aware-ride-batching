# ADR 006: Leader-Based Direction Clustering

Status: Component implemented and unit-tested; not integrated into `formBatches`.

## Decision and rationale

Group bookings by resolved pickup-zone ID, sort zones by ID and bookings by creation time/UUID, then add each booking to the first cluster whose leader bearing is within the configured angle. The first booking stays the leader. An unknown current zone uses sentinel ID -1.

This is a cheap geographic prefilter before expensive route evaluation. Leader comparison is inspectable and avoids transitive chaining through many slightly different headings. It is not a detour constraint: two members may each lie within 45 degrees of a leader while being 90 degrees apart from each other.

## Trade-offs and integration boundary

Greedy membership depends on leader choice and does not optimize grouping globally. Zone membership is recomputed and can become stale through caching; the unknown-zone group is not rejection handling.

Window/zone/direction orchestration remains unconnected. A future integration must still evaluate capacity, SLA and detour and bound actual input size; a 15-minute bucket does not establish a demand bound. See [strategy](../batching-strategy.md) and [complexity](../algorithm-analysis.md) for current execution and costs.
