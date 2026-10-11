#!/usr/bin/env python3
"""Exercise the public local fixtures through real HTTP; Python standard library only."""
import argparse
import datetime as dt
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request
import uuid

OUTPUT = Path(__file__).resolve().parents[1] / "target" / "local-review"
PASSWORD = "local-only-password"
PICKUP = {"latitude": 12.9716, "longitude": 77.5946}
DROPOFF = {"latitude": 12.8000, "longitude": 77.5946}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(base, pause=False):
    def request(method, path, token=None, body=None, employee=None, expected=200):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if employee:
            headers["X-Employee-Id"] = employee
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                status, text = response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            status, text = error.code, error.read().decode()
        require(status == expected, f"{method} {path}: expected {expected}, received {status}: {text[:600]}")
        return json.loads(text)

    def login(username):
        return request("POST", "/auth/login", body={"username": username, "password": PASSWORD})["token"]

    def capture(name, value):
        OUTPUT.mkdir(parents=True, exist_ok=True)
        (OUTPUT / (name + ".json")).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
        print(f"Verified {name} -> target/local-review/{name}.json", flush=True)

    def inspect(message):
        if pause:
            input(message + " Inspect Swagger or the saved JSON, then press Enter to continue: ")

    def get_trip(trip_id):
        return request("GET", "/api/admin/dispatch/trips/" + trip_id, admin)

    health = request("GET", "/actuator/health")
    readiness = request("GET", "/actuator/health/readiness")
    require(health["status"] == readiness["status"] == "UP", "Health/readiness must both be UP")
    admin = login("local-admin")
    require(request("GET", "/api/admin/dispatch/trips", admin)["totalElements"] == 0,
            "This run needs empty local business state. Reset the disposable Compose volume first; see the guide.")
    riders = [(str(uuid.UUID(int=n)), login(f"local-rider-{n}")) for n in range(1, 6)]
    for employee, token in riders:
        require(request("GET", "/api/v1/bookings", token, employee=employee)["totalElements"] == 0,
                "Existing bookings found. Reset only ride-batching-local before repeating this scenario.")
    capture("01-health", {"health": health, "readiness": readiness})

    # Avoid splitting a five-request burst across the real fixed UTC collection boundary.
    remaining = 900 - (time.time() % 900)
    if remaining < 20:
        print(f"Waiting {int(remaining) + 1}s for the next UTC collection window.", flush=True)
        time.sleep(remaining + 1)
    body = {"pickup": PICKUP, "dropoff": DROPOFF}
    bookings = [request("POST", "/api/v1/bookings", token, body, employee, 201)
                for employee, token in riders]
    require(len({b["id"] for b in bookings}) == 5 and all(b["status"] == "PENDING" for b in bookings),
            "Expected five distinct pending requests before batching")
    capture("02-five-requests", bookings)
    duplicate = request("POST", "/api/v1/bookings", riders[0][1], body, riders[0][0], 201)
    require(duplicate["id"] == bookings[0]["id"], "Repeated logical booking returned a different ID")
    require(request("GET", "/api/v1/bookings", riders[0][1], employee=riders[0][0])["totalElements"] == 1,
            "Duplicate created another logical booking")
    outside = request("POST", "/api/v1/bookings", riders[0][1],
                      {"pickup": {"latitude": 0, "longitude": 0}, "dropoff": DROPOFF}, riders[0][0], 422)
    require(outside["code"] == "UNSERVICEABLE_ZONE", "Expected a useful service-zone domain error")
    capture("03-input-contracts", {"duplicate": duplicate, "outOfZone": outside})

    cycle = request("POST", "/api/admin/batching/trigger", admin)
    trips = request("GET", "/api/admin/dispatch/trips", admin)["content"]
    require(cycle["bookingsConsidered"] == 5 and cycle["tripsFormed"] == 2
            and sorted(len(t["bookingIds"]) for t in trips) == [1, 4], "Expected the four-plus-one split")
    shared = next(t for t in trips if len(t["bookingIds"]) == 4)
    solo = next(t for t in trips if len(t["bookingIds"]) == 1)
    for trip in trips:
        require(trip["planningCapacity"] == 4 and trip["peakPassengers"] == len(trip["bookingIds"]),
                "Unexpected capacity or occupancy")
        require(all(s["withinPickupSla"] is True for s in trip["stops"] if s["type"] == "PICKUP"),
                "A planned pickup misses its promise")
    capture("04-four-plus-one", {"cycle": cycle, "trips": trips})
    print(f"Shared trip: {shared['tripId']}; solo trip: {solo['tripId']}", flush=True)
    inspect("Four riders share one trip; the fifth has its own trip.")
    comparison = request("GET", "/api/admin/dispatch/trips/" + shared["tripId"]
                         + "?candidateBookingId=" + solo["bookingIds"][0], admin)
    check = comparison["insertionCheck"]
    require(not check["feasible"] and check["alternativesEvaluated"] == 45
            and {r["constraintCode"] for r in check["rejections"]}
            >= {"CAPACITY", "PICKUP_SLA"}, "Expected capacity and SLA to reject sharing alternatives")
    sla = next(r for r in check["rejections"] if r["constraintCode"] == "PICKUP_SLA")
    require(dt.datetime.fromisoformat(sla["estimatedPickupAt"].replace("Z", "+00:00"))
            > dt.datetime.fromisoformat(sla["pickupSlaDeadline"].replace("Z", "+00:00")),
            "SLA example must show an estimate after its deadline")
    capture("05-sharing-reasons", comparison)
    print(f"Compare candidate booking: {solo['bookingIds'][0]}", flush=True)
    inspect("Sharing alternatives failed capacity or pickup SLA.")

    rejected_id = shared["activeOfferId"]
    require(rejected_id is not None, "No eligible driver offer; check local fixture state")
    rejected = request("POST", "/api/admin/dispatch/offers/" + rejected_id + "/reject", admin)
    first = next(o for o in rejected["offers"] if o["id"] == rejected_id)
    second = next(o for o in rejected["offers"] if o["id"] == rejected["activeOfferId"])
    require(first["status"] == "REJECTED" and first["driverId"] == str(uuid.UUID(int=101))
            and second["driverId"] == str(uuid.UUID(int=102)), "Rejection must select the eligible backup driver")
    capture("06-next-driver", rejected)
    inspect("Driver 101 rejected; eligible driver 102 now has the offer.")
    accepted = request("POST", "/api/admin/dispatch/offers/" + second["id"] + "/accept", admin)
    require(accepted["tripStatus"] == "DISPATCHED" and accepted["assignedDriverId"] == second["driverId"],
            "Acceptance did not commit the matching assignment")
    capture("07-accepted", accepted)
    inspect("Driver 102 accepted. Next, one booking will be cancelled.")

    unrelated_before = get_trip(solo["tripId"])
    cancelled_id = shared["bookingIds"][0]
    booking = next(b for b in bookings if b["id"] == cancelled_id)
    owner_token = next(token for employee, token in riders if employee == booking["employeeId"])
    cancelled = request("DELETE", "/api/v1/bookings/" + cancelled_id, owner_token, employee=booking["employeeId"])
    after = get_trip(shared["tripId"])
    unrelated_after = get_trip(solo["tripId"])
    require(cancelled["status"] == "CANCELLED" and len(after["bookingIds"]) == 3
            and len(after["stops"]) == 6 and after["peakPassengers"] == 3, "Cancellation did not replan the affected trip")
    require(unrelated_before == unrelated_after, "Cancellation changed the unrelated trip response")
    capture("08-cancellation-isolation", {"cancelled": cancelled, "affectedBefore": accepted,
            "affectedAfter": after, "unrelatedBefore": unrelated_before, "unrelatedAfter": unrelated_after})
    metrics = {name: request("GET", "/actuator/metrics/" + name) for name in
               ("bookings.deduplication_hits", "batching.trips_formed", "dispatch.rejections",
                "dispatch.acceptances", "bookings.cancellation_replans")}
    capture("09-metrics", metrics)
    capture("manifest", {"baseUrl": base, "sharedTripId": shared["tripId"], "soloTripId": solo["tripId"],
            "cancelledBookingId": cancelled_id, "bookings": bookings,
            "note": "Routes and IDs are real. Tokens are intentionally not saved. Reset local state before rerunning."})
    print("PASS: five requests, one duplicate, zone error, 4+1, capacity/SLA reasons, next driver, acceptance, isolated cancellation")
    print(f"Swagger: {base}/swagger-ui/index.html")
    print(f"Affected trip: {shared['tripId']} (now three riders); unrelated trip: {solo['tripId']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--pause", action="store_true", help="pause at four inspection points for a short live explanation")
    args = parser.parse_args()
    try:
        require(args.base_url.startswith(("http://127.0.0.1:", "http://localhost:")),
                "Use the explicitly started local-fixtures server on a loopback address")
        run(args.base_url.rstrip("/"), args.pause)
    except (RuntimeError, OSError, ValueError, KeyError, StopIteration, EOFError) as error:
        print(f"Local review failed: {error}\nStart the local-fixtures profile and use an empty disposable database. "
              "See docs/local-development.md.", file=sys.stderr)
        sys.exit(1)
