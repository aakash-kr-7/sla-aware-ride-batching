# Requirements Traceability

Source: *MoveInSync Backend Case Study 2026 - Case Study 7: On-Demand Ride Batching*, pages 1-2, sections I-IV and overall evaluation criteria. The supplied PDF is not vendored; this page paraphrases the relevant requirements so the repository remains understandable on its own. R/P/T labels below are repository navigation IDs, not official source numbering.

## Problem and priorities

Employees submit ad-hoc pickup/dropoff requests. Sharing compatible rides should reduce unnecessary cabs without trading away pickup promises, seats or acceptable rider detours. Nearby pickup and direction are grouping hints; feasibility constraints take precedence over sharing. Drivers must be eligible, and one cancellation must not reshuffle other trips. The source's 15-minute window, 20-minute SLA and four-seater example are illustrative, not a fully specified operating policy.

The eight invariants below are desired constraints. Coverage describes where code enforces them and where the end-to-end guarantee stops. **Implemented** means the stated scope has code/tests; **partial** means an integration or guard is missing. Neither label claims actual road-arrival guarantees.

| ID / source requirement | Enforcement or current implementation | Coverage / evidence |
| --- | --- | --- |
| R01 Serviceability: reject unsupported pickup | `BookingService` calls `ServiceabilityService` before insert; active circles, Haversine radius inclusion and deterministic overlap choice. | Implemented at creation; dropoff is outside scope. Cached zone changes can lag and batching does not revalidate. T01. |
| R02 Duplicate prevention: double-submit yields one booking | Same employee, normalized endpoints and slot form key; partial unique index excludes CANCELLED; losing insert reads existing ID. | Implemented for active service-created requests. Nullable direct/legacy keys and cancellation-history lookup remain limits. T02. |
| R03 Capacity: no route exceeds seats | Evaluator computes peak onboard riders; planner supplies default capacity Vehicle, one rider per booking. | Implemented during planning. Dispatch does not reconcile actual driver's capacity. T03. |
| R04 Pickup SLA: no accepted batch breaks a promise; split or escalate | Every pickup estimate <= stored deadline; no shared fit triggers a checked solo route, infeasible solo remains pending. | Partial workflow: computed feasibility/split covered; no automatic escalation or driver-approach estimate. T04. |
| R05 Detour: keep each rider's extra travel small | Shared minus direct estimated in-vehicle duration must meet both relative and absolute bounds. | Implemented computed policy; numeric bounds are chosen assumptions, not source thresholds. Road quality is unvalidated. T05. |
| R06 Driver eligibility: exclude active breaks, choose nearby available driver | Reject OFFLINE, ON_BREAK and active-trip drivers; rank remaining drivers by Haversine distance then ID. | Service behavior implemented; no driver HTTP/delivery flow. T06. |
| R07 Rejection cool-off: re-offer without immediately asking same driver | Explicit re-dispatch excludes trip-specific recent REJECTED offers. | Partial: no automatic re-offer; five minutes is chosen policy measured from offer creation, not rejection. T07. |
| R08 Local cancellation: replan just affected trip | Lock associated trip; remove cancelled rider's stops, preserve remaining order; empty trip becomes CANCELLED. | Partial execution handling: isolation covered, but no feasibility recheck, driver release or offer revoke. T08. |

## Other required flows

| ID / requirement | Current boundary | Evidence |
| --- | --- | --- |
| R09 Window + zone/direction cluster | Slot assignment and cluster component exist; runtime claim sends all pending rows directly to planner. Integration absent; time buckets do not bound demand. | T09; strategy and ADR 006 |
| R10 Late add if SLA/capacity fit | Separate insertion transaction searches PLANNED trips, locks selected trip and re-evaluates. No insertion into executing trips. | T10; mixed mutation races remain untested |
| R11 Booking data/progress | Employee/endpoints/creation time/slot/status/trip ID persisted; service flows reach PENDING, CONFIRMED, CANCELLED. | T11; PICKED_UP/DROPPED have no execution API, no requested-time input |

## Overall evaluation criteria

| ID / criterion | Submission evidence and limits |
| --- | --- |
| P01 Authentication / roles | JWT login, BCrypt and method ownership/admin/approver rules; T12. Public operational endpoints and downstream JWT error capture are disclosed. |
| P02 Time/space cost | [Algorithm analysis](algorithm-analysis.md); [dated synthetic JMH results](benchmarks/batching.md). No load or current latency guarantee. |
| P03 System failures / backup / recovery | PostgreSQL rollback and retry test T13; [failure notes](failure-modes.md). Backup/restore and broader fault-tolerance mechanisms are absent. |
| P04 OOP / layering | Java feature packages, controller/service/repository responsibilities and Trip aggregate; [architecture](architecture.md). Packages are not enforced modules; escalation read returns entities. |
| P05 Trade-offs | Architecture rationale plus dedup, clustering and caching decisions; [assumptions](assumptions.md) records underspecified policy. |
| P06 Monitoring / logging | Actuator, candidate/event metrics, correlation MDC; T14. No alerting stack or JSON encoder. Counters are not transactional success totals. |
| P07 Caching / staleness | Active-zone Caffeine list with local eviction/TTL; T15 and ADR 007. No cross-instance invalidation. |
| P08 Errors / exceptions | Selected 400/404/422 mappings and non-leaking token failures; T12. Other domain faults are not consistently mapped. |
| Submission demonstration | [Runnable demo](demo.md) and API reference; no recorded video/screenshots are committed. Commit history is retained. |

Use [test matrix](test-matrix.md) for T01-T15 assertions and coverage gaps. Implementation decisions/assessment approximations live in [assumptions](assumptions.md); future improvements are identified in [failure notes](failure-modes.md#future-improvements). Missing flows are not hidden behind standalone component tests.
