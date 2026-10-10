# Batching Algorithm Benchmark Results

## Machine/JVM Context
- **JVM**: JDK 17.0.12 (Java HotSpot 64-Bit Server VM, 17.0.12+8-LTS-286)
- **OS**: Windows
- **Tooling**: JMH 1.37
- **Threads**: 1 thread (Synchronized iterations)
- **Warmup**: 2 iterations (1s each)
- **Measurement**: 3 iterations (1s each)
- **Blackhole Mode**: Compiler

## Benchmark Parameters
- `clusterSize`: The number of `Booking` entities generated for the single batching window scenario. Ranging from 10 to 250 requests.
- All requests use deterministic geographical locations mimicking a clustered urban environment with realistic SLA deadlines.

## Raw Summary Table

| Cluster Size | Average Time (ms/op) | Error Margin (ms) |
|--------------|----------------------|-------------------|
| 10           | 0.578               | ± 2.059          |
| 25           | 5.197               | ± 4.336          |
| 50           | 10.898              | ± 7.269          |
| 100          | 43.697              | ± 14.260         |
| 250          | 280.107             | ± 281.984        |

## Visualization

```mermaid
xychart-beta
    title "Batch Planning Latency vs Cluster Size"
    x-axis "Cluster Size" ["10", "25", "50", "100", "250"]
    y-axis "Average Time (ms)" 0 --> 300
    bar [0.578, 5.197, 10.898, 43.697, 280.107]
```

## Interpretation
The results show a clear super-linear scaling trend aligned with our formal $O(N^2 \cdot S^3)$ algorithmic complexity analysis.
- For small (10) and medium (50) clusters, the batching planner finishes practically instantly (under 11 ms).
- At 100 requests, the latency is around 44 ms, still well within real-time latency budgets.
- At 250 requests within a single batched window, the computation takes ~280 ms. While higher, this is completely acceptable because batch planning occurs as a background scheduling task over multi-minute intervals, rather than blocking an interactive API response.

**Optimization Decision**: 
Because performance remains under half a second even for massive 250-request spatial clusters, **the results do not currently justify deep optimization.** Adding complex geospatial partitioning grids (e.g. S2/H3 bins) before the clusterer or memoization at this stage would introduce significant architectural complexity with little practical benefit. We will explicitly retain the clearer, greedy-insertion implementation.

## Limitations
1. **CPU/Platform Variance**: Tested locally on a single machine. Cloud deployments may have different single-thread performance characteristics.
2. **Missing Network/DB Latency**: This benchmark isolates the pure algorithms. In reality, fetching hundreds of bookings via JPA and acquiring optimistic/pessimistic locks will likely dominate the actual wall-clock execution time.
3. **Distribution of Data**: Deterministic synthetic generation may produce artificially denser or sparser clustering compared to actual rush-hour distributions.
