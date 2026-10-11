"use strict";

// Planning and authorization stay on the server; this client displays its responses.
const $ = (id) => document.getElementById(id);
const employeeId = (n) => `00000000-0000-0000-0000-${String(n).padStart(12, "0")}`;
const journeyBody = {
  pickup: { latitude: 12.9716, longitude: 77.5946 },
  dropoff: { latitude: 12.8000, longitude: 77.5946 }
};

const state = {
  step: 0,
  mode: "guided",
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
    "Connect",
    "Connect to the backend",
    "Sign in with the disposable local accounts.",
    "GET /actuator/health/readiness · POST /auth/login",
    "Connect",
    "Uses the local-fixtures accounts. Tokens stay in this browser tab; the server checks permissions on every request."
  ],
  [
    "Create requests",
    "Five riders, the same journey",
    "Five employees request the same pickup and long journey.",
    "POST /api/v1/bookings × 5",
    "Create five requests",
    "The backend assigns the collection window and pickup deadline. Four seats limit simultaneous passengers, not total bookings on every route."
  ],
  [
    "Check a retry",
    "One retry, still one booking",
    "Repeat the first booking, then send a pickup outside the service zone.",
    "POST /api/v1/bookings (duplicate + out-of-zone)",
    "Try a retry & invalid pickup",
    "Logical identity includes employee, pickup, destination and UTC slot. Repeating the journey in a later slot is a new request."
  ],
  [
    "Plan trips",
    "See who shares a cab",
    "Run the planner, then compare the two trips below.",
    "POST /api/admin/batching/trigger",
    "Run batching",
    "Candidates share a window, pickup zone and direction. Each proposed route must also pass capacity, SLA and detour checks."
  ],
  [
    "Explain the split",
    "Could the fifth rider fit?",
    "Ask the backend whether the solo rider could join the shared trip.",
    "GET /api/admin/dispatch/trips/{id}?candidateBookingId=…",
    "Check insertion alternatives",
    "A fresh, read-only route check, not historical decision replay. Existing stop order is preserved; each example reports its first failing constraint."
  ],
  [
    "Offer & assign",
    "Try the next driver",
    "Reject the first offer, then accept the next driver.",
    "POST /api/admin/dispatch/offers/{id}/reject",
    "Reject first offer",
    "Admin simulation uses the driver stored on the offer. Acceptance rechecks current approach time and constraints under row locks."
  ],
  [
    "Cancel & replan",
    "Change just one trip",
    "Cancel one shared rider and compare the two routes.",
    "DELETE /api/v1/bookings/{id}",
    "Cancel one booking",
    "Cancellation removes that rider’s stops and refreshes timing while preserving remaining stop order. It closes for the whole trip at first pickup."
  ],
  [
    "Read metrics",
    "See the recorded operations",
    "Read the counters recorded during this example.",
    "GET /actuator/metrics/{name}",
    "Read counters",
    "Counters accumulate across the running process. They are not per-visitor totals or a benchmark."
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
    $("stage-action").textContent = "Accept next driver’s offer";
    $("stage-endpoint").textContent = "POST /api/admin/dispatch/offers/{id}/accept";
  } else {
    $("stage-action").textContent = stage[4];
  }

  if (state.done.has(state.step)) {
    $("stage-action").textContent = state.step < stages.length - 1
      ? `Next: ${stages[state.step + 1][0]}` : "Walkthrough complete";
  }
  $("stage-action").disabled = state.busy || (state.done.has(state.step)
    ? state.step === stages.length - 1 : !stageAllowed());
  $("login-fields").hidden = state.step !== 0 || state.done.has(0);
  $("stage-state").textContent = state.busy
    ? "Executing request…"
    : state.done.has(state.step)
      ? "Done"
      : state.step > 0 && !state.done.has(state.step - 1)
        ? "Finish the previous step first"
        : state.step > 0 && !state.fresh
          ? "Use Your scenario with existing data"
          : state.attempted.has(actionKey())
            ? "Check the request log; use Your scenario to continue"
            : "Ready";

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
    button.disabled = state.busy || (i > 0 && !state.done.has(i - 1));
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
  const connected = state.done.has(0);
  $("custom-mode").disabled = state.busy || !connected;
  $("guided-mode").disabled = state.busy;
  $("send-scenario").disabled = state.busy || !connected;
  $("batch-scenario").disabled = state.busy || !connected;
  const pendingRows = [...$("scenario-rows").children].some((row) =>
    state.bookings.some((booking) => booking.id === row.bookingId && booking.status === "PENDING"));
  $("send-scenario").className = `btn ${pendingRows ? "btn-secondary" : "btn-primary"}`;
  $("batch-scenario").className = `btn ${pendingRows ? "btn-primary" : "btn-secondary"}`;
  $("rider-count").disabled = state.busy;
  document.querySelectorAll(".scenario-row input, .scenario-row select").forEach((input) => {
    input.disabled = state.busy;
  });
  document.querySelectorAll("[data-trip-action]").forEach((button) => {
    button.disabled = state.busy || !connected;
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
    if (!state.done.has(0)) {
      state.admin = null;
      state.riders = [];
    }
    notify(`${error.message} Check the request log for the response. Refresh before retrying a timed-out operation.`, "error");
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
      `HTTP ${response.status}: ${typeof data === "string" ? data.slice(0, 240) : `${data.code || "Request failed"} — ${data.message || "See the response in the request log."}`}`
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
  $("activity-history").replaceChildren();
  $("older-requests").hidden = state.calls.length <= 5;
  if (!state.calls.length) {
    $("activity").append(element("p", "empty-feed", "HTTP requests will appear here in real time as you run actions."));
    return;
  }

  [...state.calls].reverse().forEach((call, index) => {
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
    $(index < 5 ? "activity" : "activity-history").append(detail);
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
  const fixture = { "00000065": "Cab 1 · 4 seats", "00000066": "Cab 2 · 4 seats", "00000067": "Cab 3 · 2 seats", "00000068": "Cab 4 · offline" };
  return id ? fixture[id.slice(-8)] || `Driver …${id.slice(-8)}` : "Awaiting acceptance";
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

  [...state.trips].sort((a, b) => b.peakPassengers - a.peakPassengers).forEach((trip) => {
    const card = element("article", "trip");
    const head = element("div", "trip-top");
    head.append(
      element("h3", "", `${trip.bookingIds.length} ${trip.bookingIds.length === 1 ? "Rider" : "Riders"}`),
      element("span", "status-pill", trip.tripStatus)
    );
    card.append(head, element("p", "trip-id", `Trip …${trip.tripId.slice(-8)}`));

    const seats = element("div", "occupancy-bar");
    seats.setAttribute("aria-hidden", "true");
    for (let i = 0; i < trip.planningCapacity; i++) {
      seats.append(element("span", `seat-slot${i < trip.peakPassengers ? " occupied" : ""}`));
    }
    card.append(
      seats,
      element("p", "trip-facts", `Peak occupancy ${trip.peakPassengers} / ${trip.planningCapacity} planning seats`),
      element("p", "trip-facts", trip.bookingIds.map(riderLabel).join(", ") || "No riders"),
      element("p", "trip-facts", driverLabel(trip.assignedDriverId)),
      element("p", "trip-facts", `${trip.outcome} · ${trip.attemptsUsed}/${trip.attemptLimit} offers`)
    );
    const active = trip.offers.find((offer) => offer.id === trip.activeOfferId);
    if (active) card.append(element("p", "trip-facts", `Offered to ${driverLabel(active.driverId)}`));

    const route = element("details");
    route.append(element("summary", "", `Route & pickup times · ${trip.stops.length} stops`));
    const wrap = element("div", "table-wrap");
    const table = element("table");
    const thead = element("thead");
    const headings = element("tr");
    ["Stop", "Rider", "Estimate", "Deadline", "SLA"].forEach((label) => {
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

    const offers = element("details");
    offers.append(element("summary", "", `Driver offers · ${trip.offers.length}`));
    trip.offers.forEach((offer) => {
      const row = element("div", "offer-row");
      row.append(
        element("span", "", `${driverLabel(offer.driverId)} · expires ${timeLabel(offer.expiresAt)}`),
        element("strong", "", offer.status)
      );
      offers.append(row);
    });
    card.append(offers);
    const pickups = trip.stops.filter((stop) => stop.type === "PICKUP");
    const lastPickup = pickups.map((stop) => stop.estimatedArrivalAt).filter(Boolean).sort().at(-1);
    card.insertBefore(element("p", "pickup-summary", pickups.every((stop) => stop.withinPickupSla === true)
      ? `Pickup estimates meet all deadlines${lastPickup ? ` · last pickup ${timeLabel(lastPickup)}` : ""}`
      : "Check pickup estimates in the route table"), route);
    if (state.mode === "custom" || state.done.has(7)) addTripControls(card, trip);
    $("trips").append(card);
  });


}

async function refreshTrips() {
  const page = await api("GET", "/api/admin/dispatch/trips?size=100", { token: state.admin });
  state.trips = page.content;
  renderTrips();
  for (const row of $("scenario-rows").children) {
    if (!row.bookingId) continue;
    const booking = await api("GET", `/api/v1/bookings/${row.bookingId}`, { token: state.admin });
    rememberBooking(booking);
    showBookingResult(row, booking);
  }
}

async function connect() {
  ensure($("password").value, "Enter the local fixture password or choose Use local credentials.");
  const health = await api("GET", "/actuator/health");
  const readiness = await api("GET", "/actuator/health/readiness");
  ensure(health.status === "UP" && readiness.status === "UP", "Health and readiness checks must both be UP.");

  const password = $("password").value;
  state.admin = (await api("POST", "/auth/login", { body: { username: $("username").value.trim(), password } })).token;
  ensure(state.admin, "Admin authentication did not return a token.");

  state.riders = [];
  for (let n = 1; n <= 9; n++) {
    const token = (await api("POST", "/auth/login", { body: { username: `local-rider-${n}`, password } })).token;
    ensure(token, `Employee ${n} authentication failed.`);
    state.riders.push({ number: n, employee: employeeId(n), token });
  }

  $("password").value = "";
  $("scenario-inputs").open = true;
  await refreshTrips();

  let hasBookings = false;
  state.bookings = [];
  for (const rider of state.riders) {
    const page = await api("GET", "/api/v1/bookings", { token: rider.token, employee: rider.employee });
    hasBookings ||= page.totalElements > 0;
    page.content.forEach(rememberBooking);
  }
  state.fresh = !state.trips.length && !hasBookings;

  $("connection").textContent = "Connected · 9 riders";
  $("connection").className = "connected";
  finish(0, "Connected. Choose the five-rider example or try your own requests.");

  if (!state.fresh) {
    setMode("custom");
    notify("Saved rides found. You can inspect them or send custom requests. Reset the local database for a fresh guided example.", "warning");
  }
}

async function createFive() {
  const remaining = 900000 - ((Date.now() + state.serverOffset) % 900000);
  if (remaining < 20000) {
    notify(`Near UTC collection boundary. Waiting ${Math.ceil(remaining / 1000) + 1}s to stay within a single window…`);
    await new Promise((resolve) => setTimeout(resolve, remaining + 1000));
  }

  state.bookings = [];
  for (const rider of state.riders.slice(0, 5)) {
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

  finish(3, "Two trips formed: 4 riders + 1 rider. Both routes fit four seats and meet every pickup deadline.");
}

async function explainSplit() {
  const solo = await getTrip(state.solo);
  const comparison = await api("GET", `${tripPath(state.shared)}?candidateBookingId=${solo.bookingIds[0]}`, { token: state.admin });
  const check = comparison.insertionCheck;
  ensure(check, "No insertion diagnostic returned.");

  $("reasons-section").hidden = false;
  $("reasons").replaceChildren();
  $("reasons").append(
    element("p", "section-subtext", `${check.alternativesEvaluated} insertion alternatives checked · Feasible alternative: ${check.feasible ? "Yes" : "No"}`)
  );

  check.rejections.forEach((reason) => {
    const box = element("div", "reason-box");
    box.append(element("strong", "", `${reason.constraintCode} (${reason.alternativesRejected} permutations rejected)`));
    if (reason.constraintCode === "CAPACITY") {
      box.append(element("p", "", `Inserting rider requires peak occupancy of ${reason.examplePeakPassengers} simultaneous passengers (maximum vehicle capacity: ${comparison.planningCapacity}).`));
    } else if (reason.constraintCode === "PICKUP_SLA") {
      box.append(element("p", "", `This alternative fits ${reason.examplePeakPassengers} riders, but pickup at ${timeLabel(reason.estimatedPickupAt)} misses the ${timeLabel(reason.pickupSlaDeadline)} deadline for ${riderLabel(reason.affectedBooking)}.`));
    } else {
      box.append(element("p", "", JSON.stringify(reason)));
    }
    $("reasons").append(box);
  });

  ensure(!check.feasible
    && check.rejections.some((reason) => reason.constraintCode === "CAPACITY")
    && check.rejections.some((reason) => reason.constraintCode === "PICKUP_SLA"
      && reason.examplePeakPassengers <= comparison.planningCapacity),
    "The API did not show the expected capacity and within-capacity SLA failures. Inspect the actual result.");
  finish(4, "Some insertions need five seats. Others fit four seats but miss a pickup deadline. No feasible insertion was found.");
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
    state.results[5] = `${driverLabel(first.driverId)} rejected. ${driverLabel(replacement.driverId)} now has the offer. Accept explicitly to assign the trip.`;
    notify(state.results[5], "success");
  } else {
    const accepted = await api("POST", `/api/admin/dispatch/offers/${trip.activeOfferId}/accept`, { token: state.admin });
    await refreshTrips();

    ensure(
      accepted.tripStatus === "DISPATCHED" && accepted.assignedDriverId,
      "Driver offer acceptance failed to commit assignment."
    );
    state.dispatchPhase = 2;
    finish(5, `Trip DISPATCHED with ${driverLabel(accepted.assignedDriverId)}. Pickup estimates now include driver approach.`);
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

  finish(7, "Example complete. You can now try the trip controls or switch to Your scenario.");
  renderTrips();
}

const actions = [connect, createFive, correctness, batch, explainSplit, dispatch, cancelOne, metrics];

$("stage-action").addEventListener("click", () => {
  if (state.done.has(state.step) && state.step < stages.length - 1 && !state.busy) {
    state.step++;
    renderStage();
    $("stage-title").focus();
    return;
  }
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
  notify("Local credentials filled. Click Connect.");
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
  setMode("guided");
  $("scenario-rows").replaceChildren();
  $("rider-count").value = "9";
  $("scenario-inputs").open = true;
  renderScenarioRows();
  $("scenario-progress").textContent = "";

  $("password").value = "";
  $("connection").textContent = "Not connected";
  $("connection").className = "";
  $("reasons-section").hidden = true;
  $("metrics-section").hidden = true;
  $("activity-history").replaceChildren();
  $("older-requests").hidden = true;
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

function setMode(mode) {
  state.mode = mode;
  $("guide-panel").hidden = mode !== "guided";
  $("guide-nav").hidden = mode !== "guided";
  $("custom-panel").hidden = mode !== "custom";
  $("guided-mode").setAttribute("aria-pressed", String(mode === "guided"));
  $("custom-mode").setAttribute("aria-pressed", String(mode === "custom"));
  document.querySelector(".console-grid").classList.toggle("custom", mode === "custom");
  $("reasons-section").hidden = mode !== "guided" || !state.done.has(4);
  $("metrics-section").hidden = mode !== "guided" || !state.done.has(7);
  renderStage();
  renderTrips();
}

function rememberBooking(booking) {
  const index = state.bookings.findIndex((item) => item.id === booking.id);
  if (index < 0) state.bookings.push(booking);
  else state.bookings[index] = booking;
}

function showBookingResult(row, booking) {
  row.bookingId = booking.id;
  const result = row.querySelector(".scenario-result");
  result.className = "scenario-result";
  result.textContent = `${booking.status} · Booking …${booking.id.slice(-8)} · pickup by ${timeLabel(booking.pickupSlaDeadline)}${booking.tripId ? ` · Trip …${booking.tripId.slice(-8)}` : ""}`;
}

function renderScenarioRows() {
  const count = Number($("rider-count").value);
  if (!Number.isInteger(count) || count < 1 || count > 9) return;
  const rows = $("scenario-rows");
  while (rows.children.length > count) rows.lastElementChild.remove();
  while (rows.children.length < count) {
    const n = rows.children.length + 1;
    const row = element("div", "scenario-row");
    const riderLabel = element("label", "", `Rider ${n}`);
    const select = element("select");
    select.dataset.field = "rider";
    for (let i = 1; i <= 9; i++) select.append(new Option(`Employee ${i}`, String(i)));
    select.value = String(n);
    riderLabel.append(select);
    row.append(riderLabel);
    for (const [field, label, value, min, max] of [
      ["pickupLat", "Pickup latitude", 12.9716, -90, 90],
      ["pickupLon", "Pickup longitude", 77.5946, -180, 180],
      ["dropoffLat", "Dropoff latitude", 12.8000, -90, 90],
      ["dropoffLon", "Dropoff longitude", 77.5946, -180, 180]
    ]) {
      const group = element("label", "", label);
      const input = element("input");
      input.type = "number";
      input.step = "any";
      input.min = min;
      input.max = max;
      input.value = value;
      input.required = true;
      input.dataset.field = field;
      group.append(input);
      row.append(group);
    }
    row.append(element("div", "scenario-result"));
    row.addEventListener("change", () => {
      row.bookingId = null;
      row.querySelector(".scenario-result").textContent = "";
    });
    rows.append(row);
  }
}

async function sendScenario() {
  ensure($("rider-count").reportValidity(), "Choose between 1 and 9 riders.");
  renderScenarioRows();
  const rows = [...$("scenario-rows").children];
  const requests = rows.map((row) => {
    const field = (name) => row.querySelector(`[data-field="${name}"]`);
    for (const input of row.querySelectorAll("input")) {
      ensure(input.reportValidity(), "Enter valid coordinates for every rider.");
    }
    const rider = state.riders.find((entry) => entry.number === Number(field("rider").value));
    ensure(rider, "Connect the fixture accounts first.");
    return { row, rider, body: {
      pickup: { latitude: Number(field("pickupLat").value), longitude: Number(field("pickupLon").value) },
      dropoff: { latitude: Number(field("dropoffLat").value), longitude: Number(field("dropoffLon").value) }
    } };
  });
  ensure(new Set(requests.map((entry) => entry.rider.number)).size === rows.length,
    "Choose a different employee for each row. Use the same employee again in a later submission to try a retry.");
  state.fresh = false;
  let completed = 0;
  let succeeded = 0;
  $("scenario-progress").textContent = `Sending ${requests.length} requests…`;
  await Promise.all(requests.map(async ({ row, rider, body }) => {
    row.bookingId = null;
    const result = row.querySelector(".scenario-result");
    result.className = "scenario-result";
    result.textContent = "Sending…";
    try {
      const booking = await api("POST", "/api/v1/bookings", {
        token: rider.token, employee: rider.employee, body, expected: 201
      });
      rememberBooking(booking);
      showBookingResult(row, booking);
      succeeded++;
    } catch (error) {
      result.className = "scenario-result error";
      result.textContent = error.message;
    } finally {
      completed++;
      $("scenario-progress").textContent = `${completed}/${requests.length} responses · ${succeeded} successful`;
    }
  }));
  await refreshTrips();
  notify(`${succeeded}/${requests.length} requests succeeded. ${succeeded ? "Run batching to plan pending requests; confirmed requests have already joined a route." : "Read the row errors and adjust the inputs."}`, succeeded === requests.length ? "success" : "warning");
}

function addTripControls(card, trip) {
  const controls = element("div", "trip-actions");
  const add = (label, operation, primary = false) => {
    const button = element("button", `btn ${primary ? "btn-primary" : "btn-secondary"}`, label);
    button.dataset.tripAction = operation;
    button.disabled = state.busy;
    button.addEventListener("click", () => perform(() => operateTrip(trip.tripId, operation)));
    controls.append(button);
  };
  if (trip.tripStatus === "PLANNED") {
    const offer = trip.offers.find((entry) => entry.id === trip.activeOfferId);
    const assignedElsewhere = offer && state.trips.some((other) => other.tripId !== trip.tripId
      && other.assignedDriverId === offer.driverId && ["DISPATCHED", "IN_PROGRESS"].includes(other.tripStatus));
    if (trip.activeOfferId && (assignedElsewhere || trip.outcome === "OFFER_EXPIRED")) {
      add("Find next driver", "advance", true);
    } else if (trip.activeOfferId) {
      add("Accept offer", "accept", true);
      add("Reject offer", "reject");
    } else if (trip.attemptsUsed < trip.attemptLimit) add("Find next driver", "advance", true);
  } else if (["DISPATCHED", "IN_PROGRESS"].includes(trip.tripStatus)) {
    add("Record next stop", "execute", true);
  }
  if (trip.bookingIds.length && ["PLANNED", "DISPATCHED"].includes(trip.tripStatus)) {
    const details = element("details");
    details.append(element("summary", "", "Cancel a rider"));
    const label = element("label", "", "Booking to cancel");
    const select = element("select");
    trip.bookingIds.forEach((id) => select.append(new Option(riderLabel(id), id)));
    label.append(select);
    const cancel = element("button", "btn btn-secondary", "Cancel selected booking");
    cancel.dataset.tripAction = "cancel";
    cancel.addEventListener("click", () => perform(() => operateTrip(trip.tripId, "cancel", select.value)));
    details.append(label, cancel);
    card.append(details);
  }
  if (controls.childElementCount) card.append(controls);
}

async function operateTrip(id, operation, bookingId) {
  state.fresh = false;
  const trip = await getTrip(id);
  let message;
  if (operation === "cancel") {
    const owner = await api("GET", `/api/v1/bookings/${bookingId}`, { token: state.admin });
    const booking = await api("DELETE", `/api/v1/bookings/${bookingId}`, {
      token: state.admin, employee: owner.employeeId
    });
    rememberBooking(booking);
    message = `${riderLabel(bookingId)} cancelled. The backend replanned the affected route.`;
  } else if (operation === "execute") {
    ensure(trip.assignedDriverId && ["DISPATCHED", "IN_PROGRESS"].includes(trip.tripStatus), "Accept a driver offer first.");
    let next;
    for (const stop of trip.stops) {
      const booking = await api("GET", `/api/v1/bookings/${stop.bookingId}`, { token: state.admin });
      rememberBooking(booking);
      if ((stop.type === "PICKUP" && booking.status === "CONFIRMED") || (stop.type === "DROPOFF" && booking.status === "PICKED_UP")) {
        next = stop;
        break;
      }
    }
    ensure(next, "This trip has no unfinished stops.");
    const booking = await api("POST", `/api/admin/bookings/${next.bookingId}/${next.type === "PICKUP" ? "pickup" : "dropoff"}`, { token: state.admin });
    rememberBooking(booking);
    message = `${next.type === "PICKUP" ? "Picked up" : "Dropped off"} ${riderLabel(next.bookingId)}.`;
  } else if (operation === "advance") {
    const result = await api("POST", `${tripPath(id)}/advance`, { token: state.admin });
    message = `Dispatch: ${result.outcome}.`;
  } else {
    ensure(trip.activeOfferId, `No active offer: ${trip.outcome}. Refresh the trip.`);
    const result = await api("POST", `/api/admin/dispatch/offers/${trip.activeOfferId}/${operation}`, { token: state.admin });
    message = operation === "accept" ? `${driverLabel(result.assignedDriverId)} accepted · ${result.tripStatus}.` : `Offer rejected · ${result.outcome}.`;
  }
  await refreshTrips();
  const updated = state.trips.find((entry) => entry.tripId === id);
  if (updated?.tripStatus === "COMPLETED") message += " Trip completed; driver released.";
  notify(message, "success");
}

$("guided-mode").addEventListener("click", () => setMode("guided"));
$("custom-mode").addEventListener("click", () => setMode("custom"));
$("rider-count").addEventListener("change", renderScenarioRows);
$("rider-count").addEventListener("input", renderScenarioRows);
$("send-scenario").addEventListener("click", () => perform(sendScenario));
$("batch-scenario").addEventListener("click", () => perform(async () => {
  state.fresh = false;
  const result = await api("POST", "/api/admin/batching/trigger", { token: state.admin });
  await refreshTrips();
  $("scenario-inputs").open = false;
  notify(`${result.bookingsConsidered} requests considered · ${result.tripsFormed} trips formed · ${result.bookingsLeftPending} left pending in this cycle.${result.bookingsConsidered === 0 ? " Requests may already be confirmed, collecting, expired or locked. Check their statuses in the rows and request log." : ""}`, "success");
}));

renderScenarioRows();
renderStage();
