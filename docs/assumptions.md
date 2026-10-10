# Operational Assumptions and Defaults

The case study specifies behavioural constraints and gives illustrative durations. This table records the current implementation. Tests/benchmarks can deliberately use other settings.

| Decision | Requirement | Current interpretation/default |
| --- | --- | --- |
| D01: window | Collect in short windows | Booking slots are 15-minute UTC epoch buckets, start inclusive/end exclusive. Runtime batching does not partition by those buckets. |
| D02: SLA | Honour pickup promise | Creation time plus `app.sla.pickup-sla-minutes` (20). Pickup estimates exclude driver approach. Infeasible solo requests remain pending. |
| D03: dedup | Same employee/route/slot creates one booking | Deterministic key and partial unique index excluding `CANCELLED`. Duplicate inserts return the stored booking. |
| D04: coordinates | Protect against repeated taps/GPS jitter | Key rounds to 4 decimals, HALF_UP. Serviceability/routing use original doubles; storage does not enforce 6 decimals. Dedup precision is fixed in code. |
| D05: zones | Reject unsupported pickup | Active circular geofences; Haversine distance <= radius. Overlap: closest center then lowest numeric ID. Dropoff is not zone-checked. |
| D06: direction | Group compatible directions | Standalone leader-based zone/bearing clusterer, 45 degrees. Unit-tested but not called by runtime batch formation. |
| D07: detour | Bound extra travel | Runtime requires both relative <= 100% and absolute <= 30 minutes. Earlier draft proposed 40%/15 minutes; cleanup preserves runtime policy. Benchmark uses 40%/15 minutes. |
| D08: ETA | Estimate route duration | Haversine * 1.35 circuity, 30 km/h, 120-second dwell per destination leg. No live traffic/routing API. |
| D09: capacity | Never exceed seats | Planning uses 4 seats and one rider/booking. Capacity is segment-level. Dispatch does not check driver capacity against planned load. |
| D10: cool-off | Do not immediately re-ask rejecting driver | `dispatch.cool-off.duration=PT5M`, trip-specific rejected offers filtered by creation time, not response time. No trip revision reset. |
| D11: cancellation | Replan affected trip only | Terminal `CANCELLED`; remove/resequence stops; cancel empty trip. Repeated cancellation is accepted; `DROPPED` rejected, `PICKED_UP` currently permitted. No feasibility recheck or driver/offer revocation. |

## Configuration

`src/main/resources/application.yml` declares connections, JWT, endpoint exposure and operational defaults. `application-local.yml` adds local logging and optional environment-supplied admin bootstrap. `src/test/resources/application-test.yml` supplies test-only signing material; connection details come from Testcontainers.

`.env.example` contains empty secret placeholders. Compose reads `.env`; export its values separately for Spring Boot. Environment overrides apply to operational keys, e.g. `APP_BATCHING_MAX_RELATIVE_DETOUR_RATIO`.

The scheduler is opt-in and defaults to 5000ms fixed delay, independent of booking-slot length. These deterministic estimates are not road-network guarantees. Peak occupancy does not bound total route stops when passengers turn over.

See [batching](batching-design.md), [domain model](domain-model.md), [failure limits](failure-modes.md).
