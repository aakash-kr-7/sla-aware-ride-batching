# Browser demo

Open `/` to create ride requests, inspect shared routes and follow driver assignment. The page calls the existing authenticated APIs. Spring Boot serves its HTML, CSS and JavaScript; no frontend server or build is needed.

Start the [local fixture setup](local-development.md), then select **Use local credentials** and **Connect**. The page checks health/readiness and authenticates the admin and nine employees. Tokens stay in tab memory. Opening the page creates no bookings.

## Guided example

Use a fresh disposable database. After each action, the main button takes you to the next stage. Completed stages remain available in the navigation; later stages are disabled until their prerequisites succeed.

| Stage | What to inspect |
| --- | --- |
| Connect | Service health and separate account logins |
| Create requests | Five distinct pending bookings with server-assigned deadlines |
| Check a retry | Same booking ID on retry; an outside-zone pickup returns 422 `UNSERVICEABLE_ZONE` |
| Plan trips | Four riders share a route; one takes a separate trip. Compare occupancy and pickup times |
| Explain the split | Hypothetical insertions fail either capacity or pickup SLA; the saved route is unchanged |
| Offer & assign | Reject the first offer, then accept its replacement; the trip becomes `DISPATCHED` |
| Cancel & replan | One shared rider cancels; the affected route has three riders and the other trip is unchanged |
| Read metrics | Counters recorded by the application process |

Keep the example within about ten minutes. Pickup deadlines still apply. Near a UTC collection boundary, the initial burst waits briefly to stay in one window; a duplicate retry in a later window can legitimately create a new booking.

## Your scenario

Choose one to nine riders. Each row selects an employee and accepts pickup/dropoff latitude and longitude. The default rows use the same central pickup and long southbound journey.

**Send requests** submits the rows concurrently. Each row shows its actual result or error as the response arrives. **Run batching** then plans eligible pending bookings and refreshes their statuses. A request can already be confirmed through late insertion when it is created. The cycle's pending count covers only its claimed rows, not the entire database queue.

On a fresh database, nine default requests normally form **4 + 4 + 1**. This is an expected example, not a client-side allocation rule. Change coordinates to explore serviceability, direction grouping, seat turnover and detour/SLA checks. An invalid row does not hide other successful responses. Each employee appears once per burst; sending the same row later lets you try deduplication.

The fixture fleet has two available four-seat drivers, one available two-seat driver and one offline driver at startup. There is no fleet-editing API. Offers are provisional: two routes may initially offer the same driver. For the default nine-rider example, accept the two four-rider routes first, then the solo route so the larger cabs serve the groups that need them. Use **Find next driver** on a competing trip to consider an untried eligible driver. The backend owns availability, seat checks and the attempt limit.

[This browser capture](assets/nine-rider-demo.png) was taken on 12 October 2026 after nine default requests formed three trips and all three drivers accepted. It shows the saved state before cancellation and execution; IDs and timestamps vary on another run.

Custom mutations end the controlled example's assumptions. You can continue with the trip controls or reset the disposable database for another guided run. Saved rides found on login open directly in this mode.

## Trip controls

Each trip shows grouped riders, peak occupancy, pickup deadline checks and dispatch state. Expand **Route & pickup times** for the ordered stops or **Driver offers** for history and expiry times. Full IDs are in the response JSON.

- A planned trip with an active offer provides **Accept offer** and **Reject offer**. Acceptance can fail if the driver is now busy or the approach would miss a deadline. Refresh and find the next driver where the attempt budget permits.
- A planned trip without an offer provides **Find next driver** while attempts remain. Exhaustion stays visible in the dispatch outcome.
- An assigned trip provides **Record next stop**. The page reads booking status and submits only the next unfinished pickup/dropoff. The final dropoff completes the trip and releases its driver.
- **Cancel a rider** appears only before execution. Select the booking and cancel; the backend updates the affected route. Cancellation closes for the entire trip at first pickup.

These driver-response and execution actions are admin simulations. The page does not measure physical cab movement or actual arrival times.

## Request log and reset

The latest five calls stay visible; earlier calls are collapsed. Expand a row for the request, response, status, duration and employee header metadata. **Download JSON** exports up to 100 retained calls with login passwords and tokens redacted. Usernames, booking IDs and coordinates remain in that local export.

Mutations are not retried automatically. A timeout can occur after a commit: refresh and inspect the log before resubmitting. The page does not poll continuously; actions and **Refresh** fetch current server state. Saved pickup estimates are not recalculated by GET, and metric counters are process totals.

**Sign out** clears tab state and tokens, leaving database state intact. For a fresh run, follow [the disposable reset commands](local-development.md#stop-or-reset-the-disposable-setup). Local fixtures use a 20-minute urgency guard and ten-minute offers; normal values are five and two minutes. The 15-minute window, 20-minute pickup SLA and route constraints are unchanged.

The top-right **API reference** opens Swagger. Log in and authorize separately there for arbitrary IDs, schemas and all operations.
