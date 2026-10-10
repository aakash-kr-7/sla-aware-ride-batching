# Engineering Decisions and Operational Assumptions

This document translates case-study requirements and open questions into explicit engineering decisions.

Each decision clarifies:
1. **Case-study requirement**: What was directly specified.
2. **System interpretation**: How the requirement translates to system behaviour.
3. **Chosen assumption**: The specific design choice made for implementation.

---

## D01 — Batching Window

- **Case-study requirement**: Short collection windows (e.g. 15 minutes).
- **System interpretation**: Requests arriving within a discrete time slice are gathered before running trip formation.
- **Chosen assumption**: Fixed wall-clock intervals (e.g. 08:00–08:15, 08:15–08:30) anchored to the hour. Batch formation runs at the close of each window for all unassigned pending requests in that window.

### Context
On-demand requests arrive continuously. Evaluating every incoming request immediately against all active trips increases contention and causes unstable route churn. Grouping into discrete windows bounds computation and creates predictable batch boundaries.

### Decision
Batching evaluation runs in fixed 15-minute wall-clock windows.

### Rationale
Fixed windows provide a deterministic bucket boundary that directly doubles as a deduplication time slot. Rolling windows require continuous re-evaluation of candidate sets on every incoming request, complicating consistency.

### Known limitation
A request arriving at minute 1 of a 15-minute window waits 14 minutes before its batch is formed. This leaves 6 minutes of the 20-minute SLA budget for vehicle dispatch and pickup.

### Configuration value
`app.batching.window-duration-minutes = 15`

### Status
Accepted

---

## D02 — Pickup SLA

- **Case-study requirement**: Hard constraint H4: Pickup SLA (e.g. within 20 minutes) cannot be violated by batching.
- **System interpretation**: Every passenger in a shared trip must be picked up within their promised SLA limit.
- **Chosen assumption**: SLA is measured from the moment the booking request is accepted into the system (`booking.created_at`). The hard deadline is `booking.created_at + pickup_sla_duration`.

### Context
Batching improves vehicle occupancy by sharing rides, but must not make riders late. If adding a rider to a proposed trip delays any rider's pickup beyond their deadline, that batch configuration is infeasible.

### Decision
The default pickup SLA is 20 minutes from booking creation. A candidate trip is rejected if any passenger's estimated pickup time satisfies:
$$\text{estimated\_pickup\_time} > \text{booking.created\_at} + \text{pickup\_sla\_minutes}$$

### Rationale
Measuring from request creation reflects the rider's actual waiting time and prevents request starvation.

### Known limitation
When paired with 15-minute batching windows, requests arriving early in a window have tight pickup windows once batched. If driver availability is constrained in production, the batching window or pickup SLA configuration must be tuned accordingly.

### Configuration value
`app.sla.pickup-sla-minutes = 20`

### Status
Accepted

---

## D03 — Deduplication Slot Definition

- **Case-study requirement**: Hard constraint H2: Same employee + same pickup/drop + same slot must resolve to one booking.
- **System interpretation**: Idempotent submission protection must prevent accidental double-clicks or duplicate requests.
- **Chosen assumption**: A "slot" is the start timestamp of the 15-minute batching window containing the request time:
$$\text{slot\_id} = \lfloor \text{request\_epoch\_seconds} / \text{window\_seconds} \rfloor \times \text{window\_seconds}$$

### Context
Riders frequently double-click submit or retry network requests on poor connections. The database must enforce uniqueness across concurrent submissions.

### Decision
The deduplication key is the composite tuple:
$$(\text{employee\_id}, \text{normalized\_pickup}, \text{normalized\_drop}, \text{slot\_timestamp})$$
An active booking matching this tuple returns the existing booking rather than creating a second record.

### Rationale
Using the batching window bucket as the slot gives a clear business meaning to "slot": an employee cannot request two identical rides within the same batching dispatch window. However, they can book the same route in a later window (e.g. morning commute vs evening return).

### Known limitation
An employee cannot deliberately book two identical rides for two different colleagues under the same corporate account within the same 15-minute window. Since employee rides are individual on-demand requests, this trade-off is acceptable.

### Configuration value
Derived from `app.batching.window-duration-minutes = 15`.

### Status
Accepted

---

## D04 — Coordinate Precision and Normalisation

- **Case-study requirement**: Requests specify pickup and drop-off coordinates.
- **System interpretation**: Deduplication requires equality comparisons, but client GPS coordinates fluctuate due to sensor jitter.
- **Chosen assumption**: Coordinates are stored with 6 decimal places (~0.1m precision). For deduplication and spatial grouping, coordinates are rounded to 4 decimal places (~11m precision at the equator).

### Context
Two identical requests from the same smartphone seconds apart may report $(12.971598, 77.594562)$ and $(12.971604, 77.594558)$. Raw floating-point comparisons fail to detect these as duplicates.

### Decision
Normalise latitude and longitude to 4 decimal places before computing the deduplication key and checking zone boundaries. Persist the original 6-decimal coordinates on the booking record for routing calculations.

### Rationale
4 decimal places provides ~11-meter resolution, which effectively groups requests made from the same office gate or curb without false mismatches from GPS drift.

### Known limitation
Riders standing 25 meters apart at opposite ends of a large pickup concourse will hash to different deduplication keys.

### Configuration value
`app.geo.dedup-coordinate-precision = 4`

### Status
Accepted

---

## D05 — Representation of Serviceable Zones

- **Case-study requirement**: Hard constraint H1: Pickup must belong to a serviceable zone.
- **System interpretation**: Requests originating outside the system's operational boundaries must be rejected immediately at ingestion.
- **Chosen assumption**: Model each serviceable zone as a circular geofence with a center coordinate $(\text{lat}, \text{lon})$, radius in meters, and an active status flag.

### Context
Geographic zones can be represented as complex polygons (GeoJSON) or circular geofences. The initial requirement is to validate serviceability fast and reliably without mandating GIS extensions.

### Decision
Entity `ServiceableZone` defines:
- `id`: UUID / Long
- `name`: String
- `center_latitude`: Double
- `center_longitude`: Double
- `radius_meters`: Double
- `active`: Boolean

A pickup coordinate $(lat, lon)$ is serviceable if there exists an active zone where:
$$\text{haversine\_distance}((lat, lon), (center\_lat, center\_lon)) \le radius\_meters$$

If multiple active zones overlap, the coordinate resolves to the zone whose center is closest to the coordinate. If distances are identical, it resolves to the zone with the lowest ID.

### Rationale
Circular geofences require only the standard Haversine distance formula. This avoids heavy external GIS dependencies (like PostGIS or spatial index libraries) in the initial foundation while still providing strict validation.

### Known limitation
Circular zones do not map perfectly to irregular municipal or campus boundaries. When polygonal geofencing becomes necessary, the zone interface can be extended with polygon geometries without changing the serviceability validation contract.

### Configuration value
Seeded via migration/database.

### Status
Accepted

---

## D06 — Initial Direction-Compatibility Strategy

- **Case-study requirement**: Group riders travelling in the same general direction.
- **System interpretation**: Trips should not combine riders travelling toward opposing compass quadrants.
- **Chosen assumption**: Initial direction compatibility compares the forward azimuth (bearing angle) of each rider's direct trip vector $(pickup \to drop)$.

### Context
Forming candidate batches from all pairwise requests in a zone creates an $O(2^N)$ search space. A fast pre-filter must eliminate incompatible pairs before running detour and SLA calculations.

### Decision
Compute the forward bearing $\theta \in [0^\circ, 360^\circ)$ from pickup to dropoff using spherical trigonometry:
$$\theta = \text{atan2}(\sin \Delta\lambda \cdot \cos \phi_2, \cos \phi_1 \cdot \sin \phi_2 - \sin \phi_1 \cdot \cos \phi_2 \cdot \cos \Delta\lambda)$$
Two bookings $A$ and $B$ are direction-compatible if the angular difference $|\Delta\theta|$ satisfies:
$$|\Delta\theta| \le \text{max\_bearing\_angle\_diff\_degrees} \quad (\text{default: } 45^\circ)$$

### Rationale
Bearing checks operate in $O(1)$ time per pair using pure math, without route lookups. It rejects counter-directional rides ($180^\circ$ apart) and perpendicular rides ($90^\circ$ apart) immediately.

### Known limitation
Two riders may have similar straight-line bearings but be separated by a physical barrier (e.g. a river or highway divider) requiring a significant road detour. This pre-filter is therefore paired with the subsequent detour policy check (D07).

### Configuration value
`app.batching.max-bearing-angle-diff-degrees = 45`

### Status
Accepted

---

## D07 — Initial Detour Policy

- **Case-study requirement**: Acceptable rider detour: batching must minimise excess travel time for individual riders.
- **System interpretation**: Joining a shared trip will inevitably increase travel time compared to a solo ride, but that increase must be bounded.
- **Chosen assumption**: Enforce dual detour bounds: a maximum relative detour ratio (percentage increase over solo ride) and an absolute time cap.

### Context
A relative percentage limit alone fails on short trips (e.g. 50% detour on a 4-minute ride is only 2 minutes, which is acceptable). Conversely, an absolute cap alone fails on long trips (e.g. a 20-minute detour on a 90-minute trip may be acceptable, but not on a 15-minute trip).

### Decision
For each rider $i$ on a proposed shared trip:
1. Relative detour constraint:
$$\frac{T_{\text{shared}, i} - T_{\text{direct}, i}}{T_{\text{direct}, i}} \le \text{max\_relative\_detour\_ratio} \quad (\text{default: } 0.40 \text{ or } 40\%)$$
2. Absolute detour constraint:
$$T_{\text{shared}, i} - T_{\text{direct}, i} \le \text{max\_absolute\_detour\_minutes} \quad (\text{default: } 15 \text{ minutes})$$
A candidate shared trip is valid only if both constraints hold for every passenger in the vehicle.

### Rationale
The dual constraint protects riders on both short and long routes from unreasonable detours while giving the batching engine flexibility to fill vehicle seats.

### Known limitation
Both metrics rely on distance and speed estimates rather than live road traffic data.

### Configuration value
- `app.batching.max-relative-detour-ratio = 0.40`
- `app.batching.max-absolute-detour-minutes = 15`

### Status
Accepted

---

## D08 — Travel Time and Distance Estimation Without External Maps API

- **Case-study requirement**: System must compute distances, estimated arrival times, and SLA adherence without requiring external paid third-party APIs.
- **System interpretation**: Self-contained mathematical estimation of urban road distance and travel duration.
- **Chosen assumption**: Estimate road network distance using Haversine great-circle distance scaled by an empirical urban circuity factor ($1.35$), combined with a fixed average urban speed ($30\text{ km/h}$) and per-stop dwell time.

### Context
Calling external routing APIs (e.g. Google Distance Matrix, OSRM) introduces network latency, rate limits, external costs, and non-deterministic test failures.

### Decision
For any two stops $A$ and $B$:
$$\text{Distance}(A, B) = \text{Haversine}(A, B) \times \text{circuity\_factor}$$
$$\text{TravelTime}(A, B) = \frac{\text{Distance}(A, B)}{\text{average\_speed\_kmh}} + \text{stop\_dwell\_time}$$

### Rationale
Circuity factors ($1.30–1.40$) are standard transportation engineering approximations for urban road grids. This provides deterministic, sub-millisecond calculation suitable for algorithmic batch evaluation and fast automated testing.

### Known limitation
Does not account for one-way street loops, dynamic rush-hour congestion, or real-time road closures.

### Configuration value
- `app.geo.circuity-factor = 1.35`
- `app.geo.average-speed-kmh = 30.0`
- `app.geo.stop-dwell-time-seconds = 120` (2 minutes for rider boarding/alighting)

### Status
Accepted

---

## D09 — Vehicle Capacity

- **Case-study requirement**: Hard constraint H3: Vehicle capacity cannot be exceeded.
- **System interpretation**: The number of passengers onboard at any point along the trip's route must never exceed the assigned vehicle's seat limit.
- **Chosen assumption**: Standard fleet vehicles have a capacity of 4 passengers. Every booking request represents 1 passenger.

### Context
A multi-stop shared trip may pick up and drop off riders at different points along the route. Capacity is not simply the total count of riders over the lifetime of the trip, but the peak concurrent passenger count across all route segments.

### Decision
Default vehicle capacity is 4 seats. At every stop $k$ along the planned sequence:
$$\text{onboard\_passengers}_k \le \text{vehicle.capacity}$$
Candidate batches that would cause concurrent occupancy to exceed capacity at any segment are rejected.

### Rationale
Models the standard 4-seater corporate cab fleet. Segment-level tracking allows passenger turnover (e.g. dropping rider A before picking up rider C) if route ordering allows.

### Known limitation
Assumes single-passenger bookings. If multi-seat booking requests (e.g. employee + guest) are introduced in later phases, the booking entity will require a passenger count field.

### Configuration value
`app.vehicle.default-capacity = 4`

### Status
Accepted

---

## D10 — Driver Rejection Cool-off

- **Case-study requirement**: Hard constraint H6: A driver who rejects a trip must not immediately receive the same trip again.
- **System interpretation**: A temporal cool-off prevents repetitive dispatch loops to drivers who have declined an offer.
- **Chosen assumption**: Rejection logs record `(driver_id, trip_id, rejected_at)`. The driver is excluded from matching for that specific trip for a configurable duration (default: 10 minutes), unless the trip's composition changes.

### Context
Without cool-off, the dispatch loop would repeatedly offer the same unassigned trip to the closest driver even after rejection, locking out other available drivers and spamming the rejecting driver.

### Decision
When ranking eligible drivers for a trip, any driver who rejected that trip within `rejection_cool_off_minutes` is filtered out. If the trip's stop list changes (due to a late addition or cancellation), the trip transitions to a new revision, resetting the rejection history.

### Rationale
Applies rejection cool-off strictly to the specific trip rather than imposing a global dispatch suspension on the driver. The driver remains eligible for other distinct trips in the area.

### Known limitation
If driver supply is low, a rejected trip may wait until the cool-off expires before being re-offered to the same driver.

### Configuration value
`app.dispatch.rejection-cool-off-minutes = 10`

### Status
Accepted

---

## D11 — Booking Cancellation Semantics and Status Model

- **Case-study requirement**: Cancelling a booking must replan the affected trip locally without altering unrelated trips (Hard constraint H7).
- **System discrepancy**: `docs/problem-analysis.md` omitted `CANCELLED` from the booking status list (`PENDING`, `CONFIRMED`, `PICKED_UP`, `DROPPED`).
- **Chosen assumption**: Add `CANCELLED` as a formal terminal state in the booking lifecycle.

### Context
A cancellation cannot physically delete a database row because audit trails, slot history, and replanning triggers require the historical record.

### Decision
1. Update booking states to: `PENDING`, `CONFIRMED`, `PICKED_UP`, `DROPPED`, `CANCELLED`.
2. Valid cancellation transitions:
   - `PENDING -> CANCELLED` (booking cancelled prior to batching/assignment; dropped from batch pool).
   - `CONFIRMED -> CANCELLED` (booking cancelled after trip assignment; triggers local trip replanning).
3. Invalid transitions:
   - `PICKED_UP -> CANCELLED` (passenger is already onboard; must complete dropoff).
   - `DROPPED -> CANCELLED` (trip already completed).
4. Local trip replanning:
   - The cancelled booking's pickup and drop-off stops are removed from the trip.
   - Remaining stops in the trip are re-checked for SLA and detour constraints.
   - If the remaining trip is valid, it continues with the remaining passengers.
   - Unrelated trips are never modified (Blast radius bounded per H7).
5. Deduplication behavior on cancellation:
   - Database uniqueness constraint on the deduplication slot applies only to active bookings (`status != 'CANCELLED'`), allowing a rider to rebook if they cancelled earlier.

### Rationale
Resolves the omission in the original problem analysis document while providing explicit state machine semantics and preserving the bounded blast-radius guarantee.

### Known limitation
If all riders on a confirmed trip cancel, the trip transitions to `CANCELLED` and any assigned driver offer is revoked.

### Configuration value
N/A (state transition and invariant rule)

### Status
Accepted (corrects omission in `docs/problem-analysis.md`)