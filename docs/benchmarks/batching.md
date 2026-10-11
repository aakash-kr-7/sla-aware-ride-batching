# Batching Algorithm Benchmark Results

Historical measurements recorded on 2026-10-10, before the repository hygiene pass, infeasible-solo fix and cheapest-feasible insertion search. They have not been remeasured for the current revision. Do not treat them as current latency guarantees.

## Reproduce

JMH sources live in `src/benchmark/java`; the profile isolates its compiled/generated classes under `target/benchmark` so they do not leak into ordinary test discovery.

```sh
./mvnw -Pbenchmark -DskipTests verify
```

This compiles sources and runs the JMH harness, skipping ordinary JUnit execution for this benchmark-only command. Run normal correctness checks separately with `./mvnw test` and `./mvnw clean verify` (Windows: `./mvnw.cmd`). The harness uses 40% / 15-minute detour limits; runtime defaults are 100% / 30 minutes.

## Machine/JVM Context
- **JVM**: JDK 17.0.12 (Java HotSpot 64-Bit Server VM, 17.0.12+8-LTS-286)
- **OS**: Windows
- **Tooling**: JMH 1.37
- **Threads**: 1 benchmark thread (historical run)
- **Warmup**: 2 iterations (1s each)
- **Measurement**: 3 iterations (1s each)
- **Blackhole Mode**: Compiler

## Benchmark Parameters
- `clusterSize`: The number of `Booking` entities generated for the single batching window scenario. Ranging from 10 to 250 requests.
- All requests use deterministic geographical locations in a synthetic five-location pattern with fixed 20-minute deadlines.

## Raw Summary Table

| Cluster Size | Average Time (ms/op) | Error Margin (ms) |
|--------------|----------------------|-------------------|
| 10           | 0.578               | ± 2.059          |
| 25           | 5.197               | ± 4.336          |
| 50           | 10.898              | ± 7.269          |
| 100          | 43.697              | ± 14.260         |
| 250          | 280.107             | ± 281.984        |

## Interpretation
The recorded averages increase with cluster size, consistent with the growing candidate search described in [algorithm analysis](../algorithm-analysis.md). Error margins are broad, especially for 250 requests. These synthetic, in-memory results do not establish an end-to-end latency budget, bounded route length or production capacity. They do not justify an architectural change during repository cleanup.

## Limitations
The trial reuses mutable Booking objects whose trip IDs the planner changes; input reset and repeatability should be reviewed before recording new measurements. The harness does not invoke runtime window/clustering orchestration or driver approach/assignment validation.

1. **CPU/Platform Variance**: Tested locally on a single machine. Cloud deployments may have different single-thread performance characteristics.
2. **Missing Network/DB Latency**: This benchmark isolates the pure algorithms. The harness excludes JPA reads/writes and pessimistic row locks, so their effect on end-to-end latency is unmeasured.
3. **Distribution of Data**: Deterministic synthetic generation may produce artificially denser or sparser clustering compared to actual rush-hour distributions.
