# Algorithm Complexity Analysis

This document analyses in-memory planning and related functions. Runtime batching currently passes all pending bookings to the planner; the window and cluster algorithms are independently implemented, not wired into that cycle.

## Variables

- **$N$**: Number of pending bookings considered in one cycle or supplied cluster.
- **$Z$**: Number of active serviceable zones.
- **$C$**: Number of clusters formed ($C \le N$).
- **$T$**: Number of active candidate trips ($T \le N$).
- **$S$**: Number of stops in a single trip route. Peak seat occupancy does not bound total stops when passengers turn over; a route can contain up to $2N$ stops.
- **$D$**: Number of eligible drivers.

## 1. Pending Candidate Retrieval (`BatchingService.formBatches`)

- **Application row transfer/materialization**: $O(N)$ for N returned rows.
  The query is `status = 'PENDING' FOR UPDATE SKIP LOCKED`, with a partial status index available. Actual DB cost depends on query plan, table size and locked rows; no constant or strictly linear DB-time guarantee is claimed.
- **Space Complexity**: $O(N)$
  Allocates memory for $N$ booking entities.

## 2. Direction Clustering (`RequestClusterer.clusterBookings`)

- **Time Complexity**: $O(N \cdot Z + N \log N + N^2)$
  - Zone resolution compares $N$ bookings against $Z$ active zones: $O(N \cdot Z)$.
  - Bookings are sorted deterministically within zones: $O(N \log N)$.
  - Leader-based clustering compares each booking's bearing with the leader of each existing cluster. In the worst case (all divergent bearings), this takes $O(N^2)$.
- **Space Complexity**: $O(N)$
  Requires auxiliary space to group and store clustered bookings.

## 3. Route Insertion (`RouteInsertionPlanner.planBestInsertion`)

- **Time Complexity**: $O(S^3)$
  Evaluates all valid insertion pairs (pickup index, drop-off index) where pickup precedes drop-off. There are $O(S^2)$ such pairs. Constructing the route and calculating the travel time for each pair takes $O(S)$, resulting in $O(S^3)$.
  Route length is not explicitly capped, so $S$ cannot generally be treated as constant. The planner checks only this minimum-cost insertion for feasibility, rather than evaluating every alternative feasible route.
- **Space Complexity**: $O(S)$
  Allocates a temporary list of stops for candidate routes.

## 4. Constraint Evaluation (`TripFeasibilityEvaluator.evaluate`)

- **Time Complexity**: $O(S)$
  Performs structural/onboard checks, capacity checks and SLA/detour validation by iterating over S stops and the route-specific booking map (at most S entries). `Trip.assignRoute` separately validates repeated pickup/dropoff pairs.
- **Space Complexity**: $O(S)$
  Maintains a HashSet (`onboard`) and execution trace tracking up to $S$ passengers.

## 5. Greedy Batch Planning (`BatchPlanner.planBatch`)

- **Time Complexity**: $O(N \log N + N \cdot T \cdot S^3)$
  - Sorts bookings by SLA urgency deterministically: $O(N \log N)$.
  - For each of the $N$ bookings, evaluates insertion into $T$ existing trips. Each evaluation calls route insertion ($O(S^3)$) and feasibility ($O(S)$).
  - A conservative bound substitutes $T \le N$: $O(N^2 \cdot S^3)$. If every trip is solo, S is small; the maximum T and maximum S cannot both be attained by those same solo trips.
- **Space Complexity**: $O(N)$
  Allocates lists and maps for trips and current bookings.

## 6. Driver Ranking (`DriverProximityRanker.rankByProximity`)

- **Time Complexity**: $O(D \log D)$
  Computes the straight-line distance for $D$ eligible drivers ($O(D)$) and sorts them deterministically ($O(D \log D)$).
- **Space Complexity**: $O(D)$
  Requires memory to construct and store the sorted list of drivers.

## Algorithmic Cost and Evidence

Route insertion dominates per-candidate search. A conservative global bound is $O(N^2 \cdot S^3)$, not a measured runtime model.

For a fixed small route length, comparing bookings against candidate trips contributes quadratic work. With passenger turnover, route length can grow independently of seat capacity and the cubic insertion cost also matters. Neither the pending-row claim nor route length has an explicit runtime bound. Benchmark results cover only the recorded synthetic inputs, not a general demand or latency guarantee.
