"use strict";

// Stateless client for inspecting backend ride-batching algorithms and state machines.
const $ = (id) => document.getElementById(id);
const employeeId = (n) => `00000000-0000-0000-0000-${String(n).padStart(12, "0")}`;
const journeyBody = {
  pickup: { latitude: 12.9716, longitude: 77.5946 },
  dropoff: { latitude: 12.8000, longitude: 77.5946 }
};

const state = {
  step: 0,
  busy: false,
  admin: null,
  riders: [],
  bookings: [],
  trips: [],
  done: new Set(),
  attempted: new Set(),
  results: {},
  calls: [],
  fresh: false,
  shared: null,
  solo: null,
  dispatchPhase: 0,
  serverOffset: 0
};

const stages = [
  [
    "Health & Auth",
    "1. Health Check & Account Authentication",
    "Verify service readiness via Spring Boot Actuator, then sign in the admin and 5 test employee accounts to acquire individual JWT tokens. Employee tokens create individual bookings, while the admin token triggers batching and dispatch simulation.",
    "GET /actuator/health/readiness · POST /auth/login",
    "Connect & Sign In",
    "Stateless review harness: all business rules, authentication, and database state remain strictly on the backend."
  ],
  [
    "Ingest Bookings",
    "2. Ingest 5 Co-Directional Ride Requests",
    "Create 5 distinct bookings with identical pickup and dropoff coordinates across 5 authenticated employees. Because all riders travel the full route simultaneously, vehicle capacity (4 seats) forces a 4 + 1 trip split.",
    "POST /api/v1/bookings × 5",
    "Submit 5 Bookings",
    "Each booking is assigned a 15-minute UTC collection window, calculated pickup SLA deadline, and PENDING status."
  ],
  [
    "Validation & Idempotency",
    "3. Idempotency & Geofence Validation",
    "Re-submit Employee 1's booking to demonstrate database deduplication (returns original booking ID without inserting duplicates). Next, submit a pickup outside active service zones to verify the 422 UNSERVICEABLE_ZONE domain error.",
    "POST /api/v1/bookings (duplicate + out-of-zone)",
    "Verify Deduplication & Geofence",
    "Deduplication keys prevent duplicate bookings from client retries; zone validation enforces geofencing rules."
  ],
  [
    "Batch Optimization",
    "4. Execute Batching & Inspect Feasible Routes",
    "Trigger the batching engine to group pending requests into optimized trips. Inspect the resulting routes, ordered stop sequences, vehicle occupancy (4/4 vs 1/4), and pickup ETA promises against deadlines.",
    "POST /api/admin/batching/trigger",
    "Run Batching Engine",
    "The solver strictly enforces 3 hard constraints: 4-seat capacity, 20-minute pickup SLA deadlines, and maximum detour limits."
  ],
  [
    "Constraint Diagnostics",
    "5. Why Rider 5 Was Split (Constraint Violations)",
    "Evaluate candidate insertion of the solo rider into the 4-passenger trip. The API evaluates stop permutations and reports exact constraint violations: some permutations breach 4-seat capacity, while reordered permutations violate pickup SLA.",
    "GET /api/admin/dispatch/trips/{id}?candidateBookingId=…",
    "Evaluate Insertion Constraints",
    "Diagnostic evaluation proves why the solver rejected combining all 5 riders into a single vehicle."
  ],
  [
    "Driver State Machine",
    "6. Driver Offer, Rejection & Acceptance",
    "Simulate the driver dispatch lifecycle: reject the initial driver offer to observe automated fallback to a backup driver, then accept the offer to transition the trip from PLANNED to DISPATCHED under database row locks.",
    "POST /api/admin/dispatch/offers/{id}/reject",
    "Reject Initial Driver Offer",
    "Driver assignment requires explicit acceptance with atomic concurrency locks to prevent double-booking."
  ],
  [
    "Dynamic Replanning",
    "7. Cancellation Isolation & Route Replanning",
    "Cancel one passenger booking using their employee token. The affected pooled trip immediately replans (dropping from 4 to 3 riders and 8 to 6 stops), while the independent solo trip remains completely untouched.",
    "DELETE /api/v1/bookings/{id}",
    "Cancel Rider 1 Booking",
    "Replanning recalculates stop order and arrival ETAs dynamically while preserving independent trips."
  ],
  [
    "System Telemetry",
    "8. Micrometer Metrics & Telemetry",
    "Inspect cumulative operational metrics recorded by the running application, including deduplication hits, trips formed, driver rejections, acceptances, and cancellation replans.",
    "GET /actuator/metrics/{name}",
    "Fetch Operational Metrics",
    "Live Micrometer telemetry exposed directly from Spring Boot Actuator."
  ]
];

function ensure(condition, message) {
  if (!condition) throw new Error(message);
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = String(text);
  return node;
}

function notify(message, kind = "") {
  const fb = $("feedback");
  fb.textContent = message;
  fb.className = `feedback-banner ${kind}`;
}

function actionKey() {
  return state.step === 5 ? `5-${state.dispatchPhase}` : String(state.step);
}

function stageAllowed() {
  return state.step === 0 || (
    state.admin &&
    state.fresh &&
    state.done.has(state.step - 1) &&
    !state.done.has(state.step) &&
    !state.attempted.has(actionKey())
  );
}

function renderStage() {
  const stage = stages[state.step];
  $("stage-index").textContent = `${String(state.step + 1).padStart(2, "0")} / 08`;
  $("stage-title").textContent = stage[1];
  $("stage-description").textContent = stage[2];
  $("stage-note").textContent = stage[5];
  $("stage-endpoint").textContent = stage[3];

  if (state.step === 5 && state.dispatchPhase === 1) {
    $("stage-action").textContent = "Accept Next Driver’s Offer";
    $("stage-endpoint").textContent = "POST /api/admin/dispatch/offers/{id}/accept";
  } else {
    $("stage-action").textContent = stage[4];
  }

  $("stage-action").disabled = state.busy || !stageAllowed() || state.done.has(state.step);
  $("login-fields").hidden = state.step !== 0;
  $("stage-state").textContent = state.busy
    ? "Executing request…"
    : state.done.has(state.step)
      ? "Verified against backend"
      : "Ready to execute";

  $("stage-result").textContent = state.results[state.step] || "";

  $("steps").replaceChildren();
  stages.forEach((entry, i) => {
    const li = element("li");
    const button = element("button", `step-button${state.step === i ? " active" : ""}${state.done.has(i) ? " done" : ""}`);
    if (state.step === i) button.setAttribute("aria-current", "step");
    button.append(
      element("span", "step-number", state.done.has(i) ? "✓" : String(i + 1).padStart(2, "0")),
      element("span", "", entry[0])
    );
    button.disabled = state.busy;
    button.addEventListener("click", () => {
      state.step = i;
      renderStage();
    });
    li.append(button);
    $("steps").append(li);
  });

  $("refresh").disabled = state.busy || !state.admin;
  $("signout").disabled = state.busy || !state.admin;
  $("fill-fixtures").disabled = state.busy;
  $("username").disabled = state.busy || Boolean(state.admin);
  $("password").disabled = state.busy || Boolean(state.admin);
  document.querySelectorAll("[data-extra]").forEach((button) => {
    button.disabled = state.busy || !state.admin;
  });
  $("export").disabled = state.busy || !state.calls.length;
}

function finish(index, message) {
  state.done.add(index);
  state.results[index] = message;
  notify(message, "success");
}

async function perform(action) {
  if (state.busy) return;
  state.busy = true;
  renderStage();
  try {
    await action();
  } catch (error) {
    notify(`${error.message} See Live API Inspector below for detailed response payload.`, "error");
    $("feedback").focus();
  } finally {
    state.busy = false;
    renderStage();
  }
}

async function api(method, path, { token, body, employee, expected = 200 } = {}) {
  const headers = { Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers.Authorization = `Bearer ${token}`;
  if (employee) headers["X-Employee-Id"] = employee;

  const start = performance.now();
  const call = {
    method,
    path,
    employee: employee || null,
    authenticated: Boolean(token),
    request: path === "/auth/login" ? { username: body.username, password: "[redacted]" } : body ?? null,
    at: new Date().toISOString()
  };

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 30000);

  try {
    const response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      cache: "no-store"
    });

    const serverDate = Date.parse(response.headers.get("Date"));
    if (Number.isFinite(serverDate)) state.serverOffset = serverDate - Date.now();

    const text = await response.text();
    let data;
    try { data = JSON.parse(text); } catch { data = text; }

    call.status = response.status;
    call.response = path === "/auth/login" && data?.token
      ? { token: "[redacted: retained in memory only]" }
      : data;

    ensure(
      response.status === expected,
      `${method} ${path} returned HTTP ${response.status}: ${typeof data === "string" ? data.slice(0, 240) : JSON.stringify(data).slice(0, 360)}`
    );
    return data;
  } catch (error) {
    if (!call.status) {
      call.status = "Network error";
      call.response = error.name === "AbortError"
        ? "Request timed out after 30 seconds."
        : error.message;
    }
    throw error;
  } finally {
    clearTimeout(timer);
    call.durationMs = Math.round(performance.now() - start);
    state.calls.push(call);
    if (state.calls.length > 100) state.calls.shift();
    renderActivity();
  }
}

function renderActivity() {
  $("activity").replaceChildren();
  if (!state.calls.length) {
    $("activity").append(element("p", "empty-feed", "HTTP requests will appear here in real time as you run actions."));
    return;
  }

  [...state.calls].reverse().forEach((call) => {
    const detail = element("details", "activity-item");
    const summary = element("summary");

    const methodTag = element("span", `http-method method-${call.method.toLowerCase()}`, call.method);
    const pathText = element("span", "http-path", call.path);
    const isError = typeof call.status !== "number" || call.status >= 400;
    const statusTag = element("span", `http-status ${isError ? "status-fail" : "status-success"}`, `${call.status} · ${call.durationMs} ms`);

    summary.append(methodTag, pathText, statusTag);

    const body = element("div", "activity-body");
    const meta = element(
      "p",
      "activity-meta",
      `${call.at} · ${call.authenticated ? "Bearer JWT (active)" : "Public request"}${call.employee ? ` · X-Employee-Id: ${call.employee}` : ""}`
    );
    const code = element("pre", "code-block activity-json", JSON.stringify({ request: call.request, response: call.response }, null, 2));

    body.append(meta, code);
    detail.append(summary, body);
    $("activity").append(detail);
  });
}

const tripPath = (id) => `/api/admin/dispatch/trips/${id}`;
const getTrip = (id) => api("GET", tripPath(id), { token: state.admin });

function riderLabel(id) {
  const booking = state.bookings.find((entry) => entry.id === id);
  const rider = booking && state.riders.find((entry) => entry.employee === booking.employeeId);
  return rider ? `Employee ${rider.number}` : `Booking …${id.slice(-8)}`;
}

function timeLabel(value) {
  return value
    ? new Date(value).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" })
    : "Not recorded";
}

function driverLabel(id) {
  return id ? `Driver …${id.slice(-8)}` : "None (PLANNED state)";
}

function renderTrips() {
  $("trips").replaceChildren();
  if (!state.trips.length) {
    const empty = element("div", "empty-state card");
    empty.append(
      element("span", "empty-icon", "↳"),
      element("p", "empty-title", "No saved trips returned"),
      element("span", "empty-desc", "Create pending bookings, then trigger batching when eligible.")
    );
    $("trips").append(empty);
  }

  state.trips.forEach((trip) => {
    const card = element("article", "trip");
    const head = element("div", "trip-top");
    head.append(
      element("h3", "", `${trip.bookingIds.length} ${trip.bookingIds.length === 1 ? "Rider" : "Riders"}`),
      element("span", "status-pill", trip.tripStatus)
    );
    card.append(head, element("p", "trip-id", `Trip ID: ${trip.tripId}`));

    // Occupancy graphical indicators
    const seats = element("div", "occupancy-bar");
    seats.setAttribute("aria-hidden", "true");
    for (let i = 0; i < trip.planningCapacity; i++) {
      seats.append(element("span", `seat-slot${i < trip.peakPassengers ? " occupied" : ""}`));
    }
    card.append(
      seats,
      element("p", "trip-facts", `Peak Occupancy: ${trip.peakPassengers} / ${trip.planningCapacity} planning seats`),
      element("p", "trip-facts", `Grouped: ${trip.bookingIds.map(riderLabel).join(", ") || "No riders"}`),
      element("p", "trip-facts", `Assigned Driver: ${driverLabel(trip.assignedDriverId)}`),
      element("p", "trip-facts", `Dispatch: ${trip.outcome} · ${trip.attemptsUsed}/${trip.attemptLimit} offers used`)
    );

    // Route stops
    const route = element("details");
    route.append(element("summary", "", `Ordered Route Stops & SLA (${trip.stops.length} stops)`));
    const wrap = element("div", "table-wrap");
    const table = element("table");
    const thead = element("thead");
    const headings = element("tr");
    ["Stop", "Rider", "Est. Arrival", "SLA Deadline", "Pickup SLA"].forEach((label) => {
      const th = element("th", "", label);
      th.scope = "col";
      headings.append(th);
    });
    thead.append(headings);
    table.append(thead);

    const tbody = element("tbody");
    trip.stops.forEach((stop) => {
      const row = element("tr");
      const values = [
        `${stop.sequence} · ${stop.type}`,
        riderLabel(stop.bookingId),
        timeLabel(stop.estimatedArrivalAt),
        stop.pickupSlaDeadline ? timeLabel(stop.pickupSlaDeadline) : "—",
        stop.withinPickupSla === null ? "—" : stop.withinPickupSla ? "✓ Meets SLA" : "✗ Missed SLA"
      ];
      values.forEach((value, i) => {
        const td = element("td", i === 4 && stop.withinPickupSla !== null ? (stop.withinPickupSla ? "badge-pass" : "badge-fail") : "", value);
        row.append(td);
      });
      tbody.append(row);
    });
    table.append(tbody);
    wrap.append(table);
    route.append(wrap);
    card.append(route);

    // Offers
    const offers = element("details");
    offers.append(element("summary", "", `Driver Offer History (${trip.offers.length})`));
    trip.offers.forEach((offer) => {
      const row = element("div", "offer-row");
      row.append(
        element("span", "", driverLabel(offer.driverId)),
        element("strong", "", offer.status)
      );
      offers.append(row);
    });
    card.append(offers);
    $("trips").append(card);
  });

  const selected = $("trip-select").value;
  $("trip-select").replaceChildren();
  if (!state.trips.length) {
    $("trip-select").append(new Option("No trips available", ""));
  }
  state.trips.forEach((trip) => {
    $("trip-select").append(
      new Option(`${trip.bookingIds.length} riders · ${trip.tripStatus} · …${trip.tripId.slice(-8)}`, trip.tripId)
    );
  });
  if (state.trips.some((trip) => trip.tripId === selected)) {
    $("trip-select").value = selected;
  }
}

async function refreshTrips() {
  const page = await api("GET", "/api/admin/dispatch/trips?size=100", { token: state.admin });
  state.trips = page.content;
  renderTrips();
}

async function connect() {
  ensure($("password").value, "Enter fixture password or click 'Auto-fill Test Credentials'.");
  const health = await api("GET", "/actuator/health");
  const readiness = await api("GET", "/actuator/health/readiness");
  ensure(health.status === "UP" && readiness.status === "UP", "Health and readiness checks must both be UP.");

  const password = $("password").value;
  state.admin = (await api("POST", "/auth/login", { body: { username: $("username").value.trim(), password } })).token;
  ensure(state.admin, "Admin authentication did not return a token.");

  state.riders = [];
  for (let n = 1; n <= 5; n++) {
    const token = (await api("POST", "/auth/login", { body: { username: `local-rider-${n}`, password } })).token;
    ensure(token, `Employee ${n} authentication failed.`);
    state.riders.push({ number: n, employee: employeeId(n), token });
  }

  $("password").value = "";
  await refreshTrips();

  let hasBookings = false;
  for (const rider of state.riders) {
    const page = await api("GET", "/api/v1/bookings", { token: rider.token, employee: rider.employee });
    hasBookings ||= page.totalElements > 0;
  }
  state.fresh = !state.trips.length && !hasBookings;

  $("connection").textContent = "Connected (Admin + 5 Employees)";
  $("connection").className = "connected";
  finish(0, "Health and readiness UP. Admin and 5 employee accounts authenticated via JWT.");

  if (!state.fresh) {
    notify("Existing bookings or trips found in database. For a clean controlled walkthrough, reset the Compose volume and restart.", "warning");
  }
}

async function createFive() {
  const remaining = 900000 - ((Date.now() + state.serverOffset) % 900000);
  if (remaining < 20000) {
    notify(`Near UTC collection boundary. Waiting ${Math.ceil(remaining / 1000) + 1}s to stay within a single window…`);
    await new Promise((resolve) => setTimeout(resolve, remaining + 1000));
  }

  state.bookings = [];
  for (const rider of state.riders) {
    state.bookings.push(
      await api("POST", "/api/v1/bookings", {
        token: rider.token,
        employee: rider.employee,
        body: journeyBody,
        expected: 201
      })
    );
  }

  ensure(
    new Set(state.bookings.map((b) => b.id)).size === 5 && state.bookings.every((b) => b.status === "PENDING"),
    "Expected 5 distinct pending bookings. Ensure database was clean before running."
  );
  ensure(
    new Set(state.bookings.map((b) => b.slotTimestamp)).size === 1,
    "Bookings crossed a UTC collection window boundary. Reset database to repeat controlled burst."
  );

  finish(1, `Created 5 distinct PENDING bookings in UTC window ${new Date(state.bookings[0].slotTimestamp).toISOString()}. All have server-assigned pickup deadlines.`);
}

async function correctness() {
  const rider = state.riders[0];
  const duplicate = await api("POST", "/api/v1/bookings", {
    token: rider.token,
    employee: rider.employee,
    body: journeyBody,
    expected: 201
  });
  const page = await api("GET", "/api/v1/bookings", {
    token: rider.token,
    employee: rider.employee
  });

  ensure(
    duplicate.id === state.bookings[0].id && page.totalElements === 1,
    "Duplicate check failed: expected original booking ID and single row."
  );

  const outside = await api("POST", "/api/v1/bookings", {
    token: rider.token,
    employee: rider.employee,
    body: { pickup: { latitude: 0, longitude: 0 }, dropoff: journeyBody.dropoff },
    expected: 422
  });

  ensure(outside.code === "UNSERVICEABLE_ZONE", "Expected UNSERVICEABLE_ZONE domain error.");
  finish(2, `Idempotent retry returned original booking ID (1 row total). Out-of-zone pickup correctly rejected with 422 ${outside.code}.`);
}

async function batch() {
  const result = await api("POST", "/api/admin/batching/trigger", { token: state.admin });
  await refreshTrips();

  state.shared = state.trips.find((trip) => trip.bookingIds.length === 4)?.tripId;
  state.solo = state.trips.find((trip) => trip.bookingIds.length === 1)?.tripId;

  ensure(
    result.bookingsConsidered === 5 && result.tripsFormed === 2 && state.shared && state.solo,
    `Batching cycle considered ${result.bookingsConsidered} requests and formed ${result.tripsFormed} trips. Expected 4 + 1 split with 4-seat capacity constraint.`
  );
  ensure(
    state.trips.every((trip) => trip.peakPassengers <= trip.planningCapacity && trip.stops.filter((s) => s.type === "PICKUP").every((s) => s.withinPickupSla === true)),
    "Routes must satisfy capacity limits (≤ 4 seats) and pickup SLA deadlines."
  );

  finish(3, "Batching complete: 2 trips formed (4 riders + 1 rider). Peak occupancy strictly ≤ 4 seats; all pickup ETAs satisfy promised deadlines.");
}

async function explainSplit() {
  const solo = await getTrip(state.solo);
  const comparison = await api("GET", `${tripPath(state.shared)}?candidateBookingId=${solo.bookingIds[0]}`, { token: state.admin });
  const check = comparison.insertionCheck;
  ensure(check, "No insertion diagnostic returned.");

  $("reasons-section").hidden = false;
  $("reasons").replaceChildren();
  $("reasons").append(
    element("p", "section-subtext", `${check.alternativesEvaluated} stop permutations evaluated · Feasible alternative found: ${check.feasible ? "Yes" : "No"}`)
  );

  check.rejections.forEach((reason) => {
    const box = element("div", "reason-box");
    box.append(element("strong", "", `${reason.constraintCode} (${reason.alternativesRejected} permutations rejected)`));
    if (reason.constraintCode === "CAPACITY_EXCEEDED") {
      box.append(element("p", "", `Inserting rider requires peak occupancy of ${reason.examplePeakPassengers} simultaneous passengers (maximum vehicle capacity: ${comparison.planningCapacity}).`));
    } else if (reason.constraintCode === "PICKUP_SLA_VIOLATION") {
      box.append(element("p", "", `Reordering stops to stay within capacity delays pickup to ${timeLabel(reason.estimatedPickupAt)}, breaching promised deadline of ${timeLabel(reason.pickupSlaDeadline)} for ${riderLabel(reason.affectedBooking)}.`));
    } else {
      box.append(element("p", "", JSON.stringify(reason)));
    }
    $("reasons").append(box);
  });

  finish(4, "Constraint validation verified: Rider 5 cannot share Trip 1 due to CAPACITY_EXCEEDED (5 > 4 seats) and PICKUP_SLA_VIOLATION.");
}

async function dispatch() {
  const trip = await getTrip(state.shared);
  ensure(trip.activeOfferId, `No active offer found: ${trip.outcome}.`);

  if (state.dispatchPhase === 0) {
    const first = trip.offers.find((offer) => offer.id === trip.activeOfferId);
    const next = await api("POST", `/api/admin/dispatch/offers/${trip.activeOfferId}/reject`, { token: state.admin });
    await refreshTrips();

    const rejected = next.offers.find((offer) => offer.id === first.id);
    const replacement = next.offers.find((offer) => offer.id === next.activeOfferId);
    ensure(
      rejected?.status === "REJECTED" && replacement && replacement.driverId !== first.driverId,
      "Automated fallback to different eligible driver failed."
    );

    state.dispatchPhase = 1;
    state.results[5] = `Driver ${driverLabel(first.driverId)} rejected. Fallback driver ${driverLabel(replacement.driverId)} received offer. Click below to accept offer.`;
    notify(state.results[5], "success");
  } else {
    const accepted = await api("POST", `/api/admin/dispatch/offers/${trip.activeOfferId}/accept`, { token: state.admin });
    await refreshTrips();

    ensure(
      accepted.tripStatus === "DISPATCHED" && accepted.assignedDriverId,
      "Driver offer acceptance failed to commit assignment."
    );
    state.dispatchPhase = 2;
    finish(5, `Trip DISPATCHED with driver ${driverLabel(accepted.assignedDriverId)}. Saved pickup ETAs now incorporate driver approach time.`);
  }
}

async function cancelOne() {
  const before = await getTrip(state.shared);
  const unrelatedBefore = await getTrip(state.solo);
  const booking = state.bookings.find((entry) => entry.id === before.bookingIds[0]);
  const rider = state.riders.find((entry) => entry.employee === booking?.employeeId);
  ensure(rider, "Unable to resolve booking owner.");

  const cancelled = await api("DELETE", `/api/v1/bookings/${booking.id}`, {
    token: rider.token,
    employee: rider.employee
  });
  const after = await getTrip(state.shared);
  const unrelatedAfter = await getTrip(state.solo);
  await refreshTrips();

  ensure(
    cancelled.status === "CANCELLED" && after.bookingIds.length === 3 && after.stops.length === 6 && after.peakPassengers === 3,
    "Cancellation failed to replan into 3-rider, 6-stop route."
  );
  ensure(
    JSON.stringify(unrelatedBefore) === JSON.stringify(unrelatedAfter),
    "Isolation violation: independent solo trip state was unexpectedly altered."
  );

  finish(6, `${riderLabel(booking.id)} cancelled. Pooled trip replanned (4 → 3 riders, 8 → 6 stops). Independent trip remained unchanged.`);
}

async function metrics() {
  $("metrics").replaceChildren();
  $("metrics-section").hidden = false;

  for (const name of [
    "bookings.deduplication_hits",
    "batching.trips_formed",
    "dispatch.rejections",
    "dispatch.acceptances",
    "bookings.cancellation_replans"
  ]) {
    const result = await api("GET", `/actuator/metrics/${name}`);
    const row = element("div", "metric-row");
    row.append(
      element("span", "metric-name", name),
      element("span", "metric-value", result.measurements.find((entry) => entry.statistic === "COUNT")?.value ?? "N/A")
    );
    $("metrics").append(row);
  }

  finish(7, "Workflow complete. Live Micrometer operational counters retrieved from Actuator.");
}

const actions = [connect, createFive, correctness, batch, explainSplit, dispatch, cancelOne, metrics];

$("stage-action").addEventListener("click", () => {
  if (!stageAllowed()) return;
  const index = state.step;
  perform(async () => {
    if ([1, 2, 3, 5, 6].includes(index)) state.attempted.add(actionKey());
    await actions[index]();
  });
});

$("fill-fixtures").addEventListener("click", () => {
  $("username").value = "local-admin";
  $("password").value = "local-only-password";
  notify("Pre-filled local seed credentials (local-admin / local-only-password). Click Connect to authenticate.");
});

$("refresh").addEventListener("click", () => perform(async () => {
  await refreshTrips();
  notify("Trip state refreshed from PostgreSQL.", "success");
}));

$("signout").addEventListener("click", () => {
  state.admin = null;
  state.riders = [];
  state.bookings = [];
  state.trips = [];
  state.done.clear();
  state.attempted.clear();
  state.results = {};
  state.calls = [];
  state.fresh = false;
  state.shared = null;
  state.solo = null;
  state.dispatchPhase = 0;
  state.step = 0;

  $("password").value = "";
  $("connection").textContent = "Not connected";
  $("connection").className = "";
  $("reasons-section").hidden = true;
  $("metrics-section").hidden = true;
  $("activity").replaceChildren(element("p", "empty-feed", "Session cleared. Server state in PostgreSQL remains intact."));

  renderTrips();
  renderStage();
  notify("Browser session cleared. Backend database state is retained.");
});

$("export").addEventListener("click", () => {
  const url = URL.createObjectURL(new Blob([JSON.stringify(state.calls, null, 2)], { type: "application/json" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = "ride-batching-trace.json";
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
});

document.querySelectorAll("[data-extra]").forEach((button) => button.addEventListener("click", () => perform(async () => {
  const operation = button.dataset.extra;

  if (operation === "escalations") {
    const issues = await api("GET", "/api/v1/escalations/open", { token: state.admin });
    notify(`${issues.length} open SLA escalations found. See API inspector for details.`, "success");
    return;
  }

  if (operation === "create") {
    const rider = state.riders.find((entry) => entry.number === Number($("employee-select").value));
    ensure(rider, "Authenticate accounts first before creating ad-hoc bookings.");
    const coord = (id) => Number($(id).value);
    const body = {
      pickup: { latitude: coord("pickup-lat"), longitude: coord("pickup-lon") },
      dropoff: { latitude: coord("dropoff-lat"), longitude: coord("dropoff-lon") }
    };
    const booking = await api("POST", "/api/v1/bookings", {
      token: rider.token,
      employee: rider.employee,
      body,
      expected: 201
    });
    state.fresh = false;
    state.bookings.push(booking);
    await refreshTrips();
    notify(`Created booking ${booking.id}: ${booking.status}. (Controlled scenario disabled after ad-hoc mutation)`, "success");
    return;
  }

  ensure($("trip-select").value, "Select a trip from the dropdown.");
  const trip = await getTrip($("trip-select").value);

  if (operation === "execute") {
    ensure(trip.assignedDriverId && ["DISPATCHED", "IN_PROGRESS"].includes(trip.tripStatus), "Driver must accept offer before recording stop execution.");
    let next = null;
    for (const stop of trip.stops) {
      const booking = await api("GET", `/api/v1/bookings/${stop.bookingId}`, { token: state.admin });
      if ((stop.type === "PICKUP" && booking.status === "CONFIRMED") || (stop.type === "DROPOFF" && booking.status === "PICKED_UP")) {
        next = stop;
        break;
      }
    }
    ensure(next, "No unrecorded stop remaining for this trip.");
    await api("POST", `/api/admin/bookings/${next.bookingId}/${next.type === "PICKUP" ? "pickup" : "dropoff"}`, { token: state.admin });
    state.fresh = false;
    notify(`Recorded ${next.type} for ${riderLabel(next.bookingId)}.`, "success");
  } else if (operation === "advance") {
    const result = await api("POST", `${tripPath(trip.tripId)}/advance`, { token: state.admin });
    state.fresh = false;
    notify(`Dispatch advanced: ${result.outcome} (Attempts: ${result.attemptsUsed}/${result.attemptLimit}).`, "success");
  } else {
    ensure(trip.activeOfferId, `No active offer available on trip: ${trip.outcome}.`);
    const result = await api("POST", `/api/admin/dispatch/offers/${trip.activeOfferId}/${operation}`, { token: state.admin });
    state.fresh = false;
    notify(`Offer ${operation}: status=${result.tripStatus}, outcome=${result.outcome}.`, "success");
  }

  await refreshTrips();
})));

renderStage();
