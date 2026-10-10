# D06 - Request Clustering

## Context
Once eligible pending bookings are gathered for a discrete time window, they must be clustered into candidate trips. We need to evaluate whether two passengers' journeys can be safely combined without introducing unacceptable detours or violating SLA limits.

However, full routing evaluations are expensive ($O(N^2)$). A fast pre-filtering step is required to bundle only trips that have a reasonable chance of succeeding.

## Decision
We implement a **Greedy Leader-Based Request Clusterer** using two criteria:
1. **Serviceable Pickup Zone**: Bookings must originate in the same logical zone.
2. **Direction Compatibility**: The overall straight-line bearing (pickup to dropoff) of a booking must fall within an acceptable threshold (`app.batching.max-bearing-angle-diff-degrees`, default 45°) of the cluster's base bearing.

### Algorithm
1. Group bookings by their computed pickup `ServiceableZone`.
2. Deterministically sort bookings in each bucket by `createdAt` ascending, then `id` ascending.
3. For each booking in the bucket:
   - Compute its forward bearing.
   - Iterate through existing clusters. If the booking's bearing is within `max-bearing-angle-diff-degrees` of the cluster's **leader** (the first booking in the cluster), append it to the cluster and stop searching.
   - If no compatible cluster exists, create a new cluster with this booking as the leader.

### Complexity
- **Time**: $O(N \log N)$ for sorting, plus $O(N \cdot C)$ for clustering, where $N$ is the number of bookings per window and $C$ is the number of resulting clusters. Worst-case is $O(N^2)$ if no bookings align, but practically $N$ per 15-min window is bounded, making the algorithm highly efficient.
- **Space**: $O(N)$ to store groups and resulting candidate sets.

### Trade-offs
- Greedy clustering does not guarantee the global optimal grouping (e.g. minimizing overall variance).
- A strict leader-based approach may skew cluster bounds if the leader is an edge case.
- **Advantage**: It is deterministic, fast, understandable, and does not require a heavy machine-learning layer, satisfying our constraints for bounded complexity.
