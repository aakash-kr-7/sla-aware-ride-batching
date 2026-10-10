# Algorithm Complexity Analysis

This document provides a formal time and space complexity analysis for the implemented PURE batch-planning algorithm and related dispatch functions.

## Variables

- **$N$**: Number of pending bookings within a batching window or cluster.
- **$Z$**: Number of active serviceable zones.
- **$C$**: Number of clusters formed ($C \le N$).
- **$T$**: Number of active candidate trips ($T \le N$).
- **$S$**: Number of stops in a single trip route ($S \le 2 \times capacity$).
- **$D$**: Number of eligible drivers.

## 1. Window Candidate Retrieval (`BatchingService.formBatches`)

- **Time Complexity**: $O(N)$
  Pending bookings are fetched via a partial index on `status = 'PENDING'` using `FOR UPDATE SKIP LOCKED`. While index access is fast, streaming $N$ rows into application memory scales linearly.
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
  *Practical Bounds*: While $O(S^3)$ appears high theoretically, vehicle capacities are strictly bounded (e.g., 4 passengers). Thus, $S \le 8$, making $S^3$ extremely small. This behaves practically as $O(1)$, avoiding premature optimization for readability.
- **Space Complexity**: $O(S)$
  Allocates a temporary list of stops for candidate routes.

## 4. Constraint Evaluation (`TripFeasibilityEvaluator.evaluate`)

- **Time Complexity**: $O(S)$
  Performs structural checks (duplicate stops, missing drop-offs), capacity checks, and SLA/detour validation by iterating linearly over the route's $S$ stops.
- **Space Complexity**: $O(S)$
  Maintains a HashSet (`onboard`) and execution trace tracking up to $S$ passengers.

## 5. Greedy Batch Planning (`BatchPlanner.planBatch`)

- **Time Complexity**: $O(N \log N + N \cdot T \cdot S^3)$
  - Sorts bookings by SLA urgency deterministically: $O(N \log N)$.
  - For each of the $N$ bookings, evaluates insertion into $T$ existing trips. Each evaluation calls route insertion ($O(S^3)$) and feasibility ($O(S)$).
  - Worst case ($T = N$, every booking forms its own trip): $O(N^2 \cdot S^3)$.
- **Space Complexity**: $O(N)$
  Allocates lists and maps for trips and current bookings.

## 6. Driver Ranking (`DriverProximityRanker.rankByProximity`)

- **Time Complexity**: $O(D \log D)$
  Computes the straight-line distance for $D$ eligible drivers ($O(D)$) and sorts them deterministically ($O(D \log D)$).
- **Space Complexity**: $O(D)$
  Requires memory to construct and store the sorted list of drivers.

## Algorithmic Hotspot

The theoretical hotspot is the **Greedy Batch Planning** step, operating at $O(N^2 \cdot S^3)$. 

Because $S$ is bounded by small physical vehicle capacities, $S^3$ is effectively a small constant. However, as demand scales, the $N^2$ component (comparing $N$ bookings against up to $T \approx N$ candidate trips) will dominate. If the batching window accumulates thousands of bookings within the same zone, $N^2$ operations will cause a significant spike in CPU utilization and planning latency, making this the primary target for future spatial partitioning or heuristic optimizations.
