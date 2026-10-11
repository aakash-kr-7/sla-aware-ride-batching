# Assumptions and Assessment Approximations

The case study supplies behavior and examples, not a complete operating policy. The following are implementation decisions unless explicitly marked as source examples. Configuration defaults come from [application.yml](../src/main/resources/application.yml); tests may use different values.

| Topic | Necessary interpretation / current value | Consequence |
| --- | --- | --- |
| D01: requested time and slot | Server creation time; 15-minute UTC epoch buckets, start inclusive/end exclusive. The source's 15 minutes is illustrative. | No scheduled-time input. Slot defines dedup identity; runtime batching does not wait for or partition by it. |
| D02: pickup promise | Creation time + `app.sla.pickup-sla-minutes`, default 20; source gives 20 as an example. | Evaluated against route ETA, not actual arrival; no driver approach time. |
| D03/D04: dedup identity and coordinates | Four decimal places, HALF_UP, fixed in `DeduplicationKeyGenerator`; employee + both endpoints + slot form identity. | Similar points can deduplicate; routing/serviceability retain original doubles. Normalization does not guarantee all GPS jitter falls in one bucket. |
| D05: serviceable region | Active circular geofences; Haversine distance <= radius. Overlaps choose closest center then lowest numeric zone ID. | Pickup only; polygon boundaries and dropoff restrictions are absent. Cache staleness is described in the cache decision. |
| D06: direction | Leader bearing difference <= 45 degrees (`app.batching.max-bearing-angle-diff-degrees`). | Component implemented/tested; runtime orchestration absent. Bearing compatibility alone does not prove a small detour. |
| D07: rider detour | Extra in-vehicle duration must meet both 100% relative and 30-minute absolute limits. | Policy chosen here, not specified by the source. Some tests/JMH use 40%/15 minutes. No claim that either policy is operationally acceptable. |
| D08: travel duration | `floor(Haversine metres * 1.35 / (30 km/h in m/s)) + 120 seconds` per leg. | Assessment approximation: constant speed/circuity/dwell, no traffic or road network. Same-location legs still include dwell. |
| D09: seats | One rider/booking; `app.fleet.default-vehicle-capacity=4`. Source illustrates a four-seater. | A temporary Vehicle value enforces planning capacity; no persisted vehicle allocation. Dispatch checks actual driver capacity against peak route occupancy, including at acceptance. |
| D10: cool-off | `dispatch.cool-off.duration=PT5M`; excludes a driver for the same trip if a REJECTED offer was updated strictly after now minus duration. | Measured from rejection; equality at the expiry threshold is eligible. No trip revision reset. |
| D11: cancellation | State-idempotent; reject DROPPED, allow PICKED_UP; remove/resequence only affected trip's stops, cancel empty trip. | No feasibility recheck, driver release or offer revoke. This is partial execution-time handling. |
| Trigger policy | Scheduler off by default; enabled fixed delay is 5000ms. | Scheduling frequency is independent of slots; no automatic dispatch or escalation. |

The limits above describe computed acceptance, not real-world pickup or road-ETA guarantees. Seat capacity bounds simultaneous riders, not total route stops; neither a time window nor seat count proves bounded planning work.

## Environment and possible production work

DB password and JWT signing secret are required environment values. Compose reads `.env`; Spring Boot needs exported values. The local profile only creates an admin when explicitly enabled with supplied credentials and the account table is empty; tests use separate signing material and disposable DBs. See [demo](demo.md) for setup and [traceability](requirements-traceability.md) for requirement coverage.

Road-network/traffic ETA, bounded claim sizes and complete execution/cancellation handling are future improvements. They are not properties of the current submission.
