# Interactive Reviewer Console

The web interface served at `/` provides an interactive review console for the SLA-aware ride batching backend. It exercises the complete end-to-end lifecycle—from booking ingestion to constraint-checked pooling, driver offer state machines, and cancellation replanning—against the live Spring Boot application and PostgreSQL database.

Spring Boot serves the static HTML, CSS, and JavaScript directly from `src/main/resources/static/`. There are zero frontend frameworks, build steps, Node/npm dependencies, or external CDNs. All routing, optimization algorithms, and transactional state machines reside exclusively on the backend.

---

## Getting Started

1. Start the local database and launch the application with the fixtures profile:
   ```bash
   docker compose -f compose.local.yml up -d --wait
   .\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local-fixtures'
   ```
2. Open **[http://127.0.0.1:8080/](http://127.0.0.1:8080/)**.
3. Click **Auto-fill Test Credentials**, then **Connect & Sign In**.

The console verifies service health and readiness endpoints (`/actuator/health/readiness`), then authenticates the admin account and five test employee accounts (`/auth/login`). JWT tokens are stored solely in browser memory and passed via `Authorization: Bearer <token>` on subsequent requests.

Opening the page performs no database mutations. Business endpoints strictly enforce authentication, role checks, and employee resource ownership.

---

## 8-Stage Sequential Verification Flow

The console walks through the system lifecycle in eight deliberate steps:

| Stage | Action Button | Backend Mechanics & Invariants Demonstrated |
| :--- | :--- | :--- |
| **01. Health & Auth** | Connect & Sign In | Verifies Actuator UP status and logs in 6 accounts to acquire separate JWTs. |
| **02. Ingest Bookings** | Submit 5 Bookings | Ingests 5 bookings for 5 distinct employees. Server assigns 15-minute UTC collection windows and 20-minute pickup SLA deadlines. |
| **03. Validation & Idempotency** | Verify Deduplication & Geofence | Re-submits Employee 1's booking to demonstrate idempotency keys (returns existing ID with no duplicate row). Submits coordinates outside Bangalore zones to verify the `422 UNSERVICEABLE_ZONE` domain error. |
| **04. Batch Optimization** | Run Batching Engine | Executes the batching solver. With 5 riders traveling simultaneously in a 4-seat capacity setup, the engine partitions requests into a 4-rider pooled trip and a 1-rider independent trip. |
| **05. Constraint Diagnostics** | Evaluate Insertion Constraints | Evaluates why Rider 5 cannot join Trip 1. The API explores stop insertion permutations and returns exact constraint violations: permutations either breach 4-seat capacity or delay pickup past the 20-minute SLA deadline. |
| **06. Driver State Machine** | Reject Initial Offer → Accept Fallback Offer | Phase 1 rejects Driver 101's offer, triggering automated dispatch fallback to backup Driver 102. Phase 2 accepts the offer, locking the assignment and transitioning trip status to `DISPATCHED`. |
| **07. Dynamic Replanning** | Cancel Rider 1 Booking | Rider 1 cancels their booking via `DELETE /api/v1/bookings/{id}`. The pooled trip dynamically replans (dropping from 4 to 3 riders and 8 to 6 stops), while the independent solo trip remains completely untouched. |
| **08. System Telemetry** | Fetch Operational Metrics | Reads live Micrometer counters from Actuator (`/actuator/metrics/*`), verifying recorded hits for deduplication, trips formed, rejections, acceptances, and replans. |

---

## Verification Controls & Evidence

### 1. Saved Trips & Routes
Inspects real database state from PostgreSQL:
- **Vehicle Occupancy Bar:** Visual representation of vehicle capacity (4 slots) vs. peak concurrent passengers.
- **Ordered Stop Sequences:** Table of sequenced PICKUP and DROPOFF stops, including estimated arrival times, promised SLA deadlines, and on-time status checks.
- **Driver Offer History:** Complete log of driver IDs, dispatch outcomes, and offer expiry timestamps.

### 2. Live API Inspector
The traffic log captures every HTTP request sent to the backend:
- Displays method (`GET`, `POST`, `DELETE`), endpoint, HTTP response code, and latency in milliseconds.
- Expand any row to inspect full request payloads, headers (with employee identities), and formatted response bodies.
- Click **Export JSON Trace** to download all recorded HTTP traces as a `.json` file for audit or review.

### 3. Architecture & Optimization Pipeline
A collapsible overview illustrates how the backend processes requests:
1. **Window Collection:** Groups pending bookings into 15-minute UTC collection windows `[t_start, t_end)`.
2. **Spatial Partitioning:** Prunes search space by serviceable pickup zones and directional headings.
3. **Feasible Insertion Search:** Evaluates stop order permutations against capacity (4 seats), pickup deadlines (≤ 20 min), and maximum detour limits.
4. **Transactional Commit:** Locks database rows (`SELECT FOR UPDATE`) to prevent race conditions during driver offer dispatch and assignment.

### 4. Manual Operations Sandbox
Allows executing ad-hoc backend mutations using the active session:
- Create custom employee bookings with custom latitude/longitude coordinates.
- Advance dispatch attempts or simulate driver accept/reject actions.
- Record sequential stop arrivals (PICKUP / DROPOFF) to simulate trip execution.
- Inspect open SLA escalations for unassigned requests.

---

## Swagger UI Link

The top-right header button **Try it yourself in Swagger ↗** opens `/swagger-ui/index.html` in a separate tab. Reviewers can explore the full OpenAPI specification, test arbitrary payloads, and verify endpoints independently.
