# Algorithm Complexity Analysis

This document analyses in-memory planning and related functions. Runtime batch formation claims at most the configured limit (default 100), partitions by source window, then clusters by active pickup zone and direction before planning. The separate late-insertion path remains unbounded.

## Variables

- **$N$**: Number of claimed bookings in one cycle (bounded by the configured limit), or bookings supplied to a standalone planner.
- **$Z$**: Number of active serviceable zones.
- **$C$**: Number of clusters formed ($C \le N$).
- **$T$**: Number of active candidate trips ($T \le N$).
- **$S$**: Number of stops in a single trip route. Peak seat occupancy does not bound total stops when passengers turn over; a route can contain up to $2N$ stops.
- **$D$**: Number of eligible drivers.

## 1. Pending Candidate Retrieval (`BatchingService.formBatches`)

- **Application row transfer/materialization**: $O(N)$ for N returned rows.
  The query filters unassociated PENDING rows by creation/deadline and closed-window/urgent eligibility, orders by deadline/creation/ID, applies LIMIT, then `FOR UPDATE SKIP LOCKED`. V12 supplies a matching partial ordered index. Actual DB cost depends on query plan, table size, open-window filtering and locked rows; LIMIT bounds returned rows, not scan time. Window partitioning adds O(N) application time/space.
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
  Route length is bounded by twice the claimed cluster size for newly formed trips, but can grow through the separate late-add path, so $S$ cannot generally be treated as constant. Runtime supplies a feasibility predicate for each promising alternative. Both costing and constraint checks are O(S) per insertion, so choosing the cheapest feasible route retains O(S³) time and streaming O(S) space.
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
  - For each of the $N$ bookings, evaluates insertion into $T$ existing trips. Each trip search enumerates/costs insertion alternatives and evaluates feasibility where they can improve the accepted cost, together O(S³).
  - A conservative bound substitutes $T \le N$: $O(N^2 \cdot S^3)$. If every trip is solo, S is small; the maximum T and maximum S cannot both be attained by those same solo trips.
- **Space Complexity**: $O(N)$
  Allocates lists and maps for trips and current bookings.

## 6. Driver Ranking (`DriverProximityRanker.rankByProximity`)

- **Time Complexity**: $O(D \log D)$
  Computes the straight-line distance for $D$ eligible drivers ($O(D)$) and sorts them deterministically ($O(D \log D)$).
- **Space Complexity**: $O(D)$
  Requires memory to construct and store the sorted list of drivers.

## Algorithmic Cost and Evidence

Serviceability scans Z cached active zones in O(Z) time; cache hits avoid a DB read, not the geometric scan. Dispatch loads all drivers and performs a cool-off lookup for each status/assignment-eligible driver and an O(S) approach/SLA/detour check against the stored route before sorting. Each selection/readiness check loads its booking map once rather than once per driver. That introduces up to one DB query per such driver; ranking complexity alone is not dispatch latency.

Late insertion loads all PLANNED candidates and their booking data. For T trips with K total stops and maximum route length S, application work is O(K + T*S^3), followed by another O(S^3) search after locking one trip; materialized state is O(T + K + S). DB reads, refresh and lock wait time are additional. No candidate/route cap makes those costs constant.

Offer feasibility also adds O(D*S) application work; new acceptance repeats O(S) constraints after locking the current driver. Route insertion dominates per-candidate planning search. A conservative global bound is $O(N^2 \cdot S^3)$, not a measured runtime model.

For a fixed small route length, comparing bookings against candidate trips contributes quadratic work. For new batch formation N is limited and S <= 2N; work is summed over separate clusters rather than one global claim. With late-add passenger turnover, route length can grow independently of seat capacity and the cubic insertion cost also matters. The separate late-insertion scans are not capped. Benchmark results cover only the recorded synthetic inputs, not a general demand or latency guarantee.
