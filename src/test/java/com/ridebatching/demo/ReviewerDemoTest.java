package com.ridebatching.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridebatching.auth.*;
import com.ridebatching.batching.evaluator.TripFeasibilityEvaluator;
import com.ridebatching.booking.*;
import com.ridebatching.dispatch.*;
import com.ridebatching.driver.*;
import com.ridebatching.fleet.Vehicle;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.*;
import com.ridebatching.zone.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in demonstration against Compose PostgreSQL and a real embedded HTTP server. */
@EnabledIfSystemProperty(named = "demo.enabled", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18080", "app.batching.scheduling.enabled=false",
        "app.batching.window-duration-minutes=15", "app.fleet.default-vehicle-capacity=4",
        "app.sla.pickup-sla-minutes=20", "app.geo.circuity-factor=1.35",
        "app.geo.average-speed-kmh=30", "app.geo.stop-dwell-time-seconds=120",
        "app.batching.max-relative-detour-ratio=1.0", "app.batching.max-absolute-detour-minutes=30",
        "dispatch.cool-off.duration=PT5M"
})
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@ActiveProfiles("demo")
class ReviewerDemoTest {
    private static final GeoPoint PICKUP = new GeoPoint(12.9716, 77.5946);
    private static final GeoPoint DROPOFF = new GeoPoint(12.8000, 77.5946);
    private static final Path OUTPUT = Path.of("target", "demo");
    @Autowired ObjectMapper json;
    @Autowired EmployeeRepository employees;
    @Autowired BookingRepository bookings;
    @Autowired TripRepository trips;
    @Autowired UserAccountRepository accounts;
    @Autowired ServiceableZoneRepository zones;
    @Autowired DriverRepository drivers;
    @Autowired DriverOfferRepository offers;
    @Autowired DispatchService dispatch;
    @Autowired DriverEligibilityPolicy eligibility;
    @Autowired RejectionCoolOffPolicy coolOff;
    @Autowired TripFeasibilityEvaluator evaluator;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<UUID, String> labels = new LinkedHashMap<>();
    private String admin;
    private String rider;

    @Test
    void demonstrateBookingPlanningDispatchAndIsolatedReplanning() throws Exception {
        Files.createDirectories(OUTPUT);
        // Refuse to alter a populated database, even if invoked without the wrapper.
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL())
                    .as("Use only the isolated ride_batching_demo database")
                    .endsWith("/ride_batching_demo");
        }
        assertThat(bookings.count()).as("Run scripts/demo/demo.py reset before repeating").isZero();
        assertThat(trips.count()).isZero();
        assertThat(accounts.count()).isZero();
        assertThat(employees.count()).isZero();
        assertThat(zones.count()).isZero();
        assertThat(drivers.count()).isZero();

        JsonNode health = request("GET", "/actuator/health", null, null, null, 200);
        JsonNode ready = request("GET", "/actuator/health/readiness", null, null, null, 200);
        assertThat(health.path("status").asText()).isEqualTo("UP");
        assertThat(ready.path("status").asText()).isEqualTo("UP");
        assertThat(send("GET", "/actuator/prometheus", null, null, null).statusCode()).isEqualTo(200);
        stage("01-readiness", "GET /actuator/health and /actuator/health/readiness (includes DB)",
                Map.of("health", health, "readiness", ready,
                        "postgres", jdbc.queryForObject("select version()", String.class)));
        seed();
        admin = login("reviewer-admin");
        rider = login("reviewer-rider");
        request("GET", "/v3/api-docs", null, null, null, 200);
        request("POST", "/api/v1/bookings", rider, id(2), body(PICKUP, DROPOFF), 403);
        stage("02-authentication", "POST /auth/login; real JWTs used for subsequent requests",
                Map.of("admin", "reviewer-admin / ADMIN", "rider", "reviewer-rider / EMPLOYEE, employee 1",
                        "wrongEmployeeHeaderStatus", 403, "tokens", "omitted from evidence"));

        // A dedup slot is wall-clock based. Start away from its boundary instead of racing it.
        long remaining = 900 - Instant.now().getEpochSecond() % 900;
        if (remaining < 10) Thread.sleep((remaining + 1) * 1000);
        JsonNode first = create(1, rider);
        UUID firstId = UUID.fromString(first.path("id").asText());
        labels.put(firstId, "R1");
        assertThat(first.path("status").asText()).isEqualTo("PENDING");
        stage("03-valid-booking", "POST /api/v1/bookings as employee 1", first);
        JsonNode duplicate = create(1, rider);
        JsonNode listing = request("GET", "/api/v1/bookings", rider, id(1), null, 200);
        assertThat(duplicate.path("id").asText()).isEqualTo(firstId.toString());
        assertThat(listing.path("totalElements").asInt()).isEqualTo(1);
        assertThat(bookings.count()).isEqualTo(1);
        stage("04-deduplication", "Repeat POST, then GET /api/v1/bookings as employee 1",
                Map.of("first", first, "repeat", duplicate, "listing", listing, "databaseBookingCount", bookings.count()));
        JsonNode outside = request("POST", "/api/v1/bookings", rider, id(1),
                body(new GeoPoint(0, 0), DROPOFF), 422);
        assertThat(outside.path("code").asText()).isEqualTo("UNSERVICEABLE_ZONE");
        assertThat(outside.path("message").asText()).contains("outside all active serviceable zones");
        assertThat(bookings.count()).isEqualTo(1);
        stage("05-zone-error", "POST /api/v1/bookings with pickup (0, 0)", outside);

        List<JsonNode> created = new ArrayList<>(List.of(first));
        for (int n = 2; n <= 5; n++) {
            JsonNode booking = create(n, admin);
            assertThat(booking.path("status").asText()).isEqualTo("PENDING");
            labels.put(UUID.fromString(booking.path("id").asText()), "R" + n);
            created.add(booking);
        }
        stage("06-controlled-requests", "POST /api/v1/bookings for employees 2 through 5 as ADMIN", created);
        List<Booking> five = labels.keySet().stream().map(key -> bookings.findById(key).orElseThrow()).toList();
        var capacityTrace = evaluator.evaluate(packedRoute(five), bookingMap(five),
                new Vehicle(id(200), 4), Instant.now(), five.get(4).getId());
        assertThat(capacityTrace.isRejectedBy("CAPACITY")).isTrue();
        stage("07-capacity-decision", "TripFeasibilityEvaluator.evaluate: proposed five-rider shared leg",
                Map.of("seats", 4, "proposedPeak", 5, "evaluated", capacityTrace.constraintsEvaluated(),
                        "rejection", capacityTrace.rejectionDetails()));
        JsonNode batch = trigger();
        assertThat(batch.path("bookingsConsidered").asInt()).isEqualTo(5);
        assertThat(batch.path("tripsFormed").asInt()).isEqualTo(2);
        assertThat(batch.path("bookingsLeftPending").asInt()).isZero();
        List<Trip> capacityTrips = readTrips();
        capacityTrips.sort(Comparator.comparingInt(Trip::getPeakPassengers).reversed());
        assertThat(capacityTrips.stream().map(Trip::getPeakPassengers)).containsExactly(4, 1);
        assertThat(capacityTrips.stream().map(t -> t.getStops().size())).containsExactly(8, 2);
        assertThat(capacityTrips.get(0).getStops().stream().filter(s -> s.getType() == TripStopType.PICKUP)
                .map(s -> labels.get(s.getBookingId()))).containsExactlyInAnyOrder("R1", "R2", "R3", "R4");
        assertThat(capacityTrips.get(1).getStops().stream().filter(s -> s.getType() == TripStopType.PICKUP)
                .map(s -> labels.get(s.getBookingId()))).containsExactly("R5");
        UUID fullTripId = capacityTrips.get(0).getId();
        UUID soloTripId = capacityTrips.get(1).getId();
        for (Booking booking : five) {
            assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        }
        stage("08-four-plus-one", "POST /api/admin/batching/trigger; read committed trip aggregates",
                Map.of("batch", batch, "trips", capacityTrips.stream().map(this::snapshot).toList()));

        // Fixture creation uses the domain/repository, because the HTTP request has no deadline field.
        // These represent already queued urgent requests, not edits to existing bookings.
        Instant queuedAt = Instant.now();
        List<Booking> urgent = new ArrayList<>();
        for (int n = 7; n <= 8; n++) {
            Booking booking = new Booking(id(300 + n), employees.findById(id(n)).orElseThrow(),
                    PICKUP, DROPOFF, com.ridebatching.batching.BatchingWindow.forTime(
                            queuedAt.minusSeconds(1110), Duration.ofMinutes(15)).start(),
                    BookingStatus.PENDING, queuedAt.plusSeconds(90),
                    queuedAt.minusSeconds(1110), "demo-urgent-" + n);
            labels.put(booking.getId(), "S" + (n - 6));
            urgent.add(booking);
        }
        bookings.saveAllAndFlush(urgent);
        List<Object> slaTraces = new ArrayList<>();
        for (List<Booking> order : List.of(urgent, List.of(urgent.get(1), urgent.get(0)))) {
            var trace = evaluator.evaluate(packedRoute(order), bookingMap(urgent),
                    new Vehicle(id(200), 4), queuedAt, order.get(1).getId());
            assertThat(trace.isRejectedBy("PICKUP_SLA")).isTrue();
            slaTraces.add(Map.of("evaluated", trace.constraintsEvaluated(), "rejection", trace.rejectionDetails()));
        }
        JsonNode slaBatch = trigger();
        assertThat(slaBatch.path("bookingsConsidered").asInt()).isEqualTo(2);
        assertThat(slaBatch.path("tripsFormed").asInt()).isEqualTo(2);
        assertThat(slaBatch.path("bookingsLeftPending").asInt()).isZero();
        UUID slaTripA = bookings.findById(urgent.get(0).getId()).orElseThrow().getTripId();
        UUID slaTripB = bookings.findById(urgent.get(1).getId()).orElseThrow().getTripId();
        assertThat(slaTripA).isNotEqualTo(slaTripB);
        stage("09-sla-split", "Seed two queued domain requests, evaluate both pickup orders, POST batching trigger",
                Map.of("remainingPickupBudgetSeconds", 90, "dwellSeconds", 120, "traces", slaTraces,
                        "batch", slaBatch, "trips", List.of(snapshot(readTrip(slaTripA)), snapshot(readTrip(slaTripB)))));

        Driver near = drivers.findById(id(101)).orElseThrow();
        assertThat(eligibility.evaluate(near).isEligible()).isTrue();
        DriverOffer firstOffer = dispatch.dispatchTrip(fullTripId, PICKUP).orElseThrow();
        assertThat(firstOffer.getDriverId()).isEqualTo(id(101));
        assertThat(firstOffer.getStatus()).isEqualTo(DriverOfferStatus.OFFERED);
        stage("10-eligible-offer", "DispatchService.dispatchTrip(fullTripId, pickup)",
                Map.of("offer", firstOffer, "drivers", drivers.findAll().stream().map(d -> Map.of(
                        "id", d.getId(), "name", d.getName(), "capacity", d.getVehicleCapacity(),
                        "eligibility", eligibility.evaluate(d), "location", d.getLocation())).toList()));
        dispatch.rejectOffer(firstOffer.getId());
        DriverOffer rejected = offers.findById(firstOffer.getId()).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(DriverOfferStatus.REJECTED);
        assertThat(coolOff.isExcludedByCoolOff(id(101), fullTripId, Instant.now())).isTrue();
        stage("11-reject-and-exclude", "DispatchService.rejectOffer; RejectionCoolOffPolicy.isExcludedByCoolOff",
                Map.of("offer", rejected, "excluded", true, "scope", "same driver and same trip",
                        "eligibleAgainAfter", rejected.getUpdatedAt().plusSeconds(300)));
        DriverOffer backup = dispatch.dispatchTrip(fullTripId, PICKUP).orElseThrow();
        assertThat(backup.getDriverId()).isEqualTo(id(102));
        stage("12-next-driver", "DispatchService.dispatchTrip again", backup);
        dispatch.acceptOffer(backup.getId());
        Trip assigned = readTrip(fullTripId);
        assertThat(assigned.getStatus()).isEqualTo(TripStatus.DISPATCHED);
        assertThat(assigned.getDriverId()).isEqualTo(id(102));
        assertThat(drivers.findById(id(102)).orElseThrow().getActiveTripId()).isEqualTo(fullTripId);
        assertThat(offers.findById(backup.getId()).orElseThrow().getStatus()).isEqualTo(DriverOfferStatus.ACCEPTED);
        stage("13-acceptance", "DispatchService.acceptOffer", Map.of("trip", snapshot(assigned),
                "offer", offers.findById(backup.getId()).orElseThrow(),
                "driverActiveTripId", drivers.findById(id(102)).orElseThrow().getActiveTripId()));

        JsonNode unrelatedBefore = json.valueToTree(snapshot(readTrip(fullTripId)));
        JsonNode soloBefore = json.valueToTree(snapshot(readTrip(soloTripId)));
        JsonNode late = create(6, admin);
        UUID lateId = UUID.fromString(late.path("id").asText());
        labels.put(lateId, "L1");
        assertThat(late.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(late.path("tripId").asText()).isEqualTo(soloTripId.toString());
        assertThat(readTrip(soloTripId).getStops()).hasSize(4);
        assertThat(readTrip(soloTripId).getPeakPassengers()).isEqualTo(2);
        stage("14-late-insertion", "POST /api/v1/bookings for employee 6; no batching trigger",
                Map.of("booking", late, "before", soloBefore, "after", snapshot(readTrip(soloTripId))));
        JsonNode affectedBefore = json.valueToTree(snapshot(readTrip(soloTripId)));
        JsonNode cancelled = request("DELETE", "/api/v1/bookings/" + lateId, admin, id(6), null, 200);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        Trip replanned = readTrip(soloTripId);
        assertThat(replanned.getStops()).hasSize(2);
        assertThat(replanned.getStops().stream().map(TripStop::getSequenceOrder)).containsExactly(1, 2);
        assertThat(replanned.getStops().stream().map(TripStop::getBookingId)).doesNotContain(lateId);
        JsonNode unrelatedAfter = json.valueToTree(snapshot(readTrip(fullTripId)));
        assertThat(unrelatedAfter).as("Unrelated trip: stops, IDs, order, times, status, assignment all unchanged")
                .isEqualTo(unrelatedBefore);
        stage("15-cancellation-isolation", "DELETE /api/v1/bookings/{lateId} as ADMIN with employee 6 header",
                Map.of("booking", cancelled, "affectedBefore", affectedBefore,
                        "affectedAfter", snapshot(replanned), "unrelatedBefore", unrelatedBefore,
                        "unrelatedAfter", unrelatedAfter, "unrelatedExactlyEqual", true));

        Map<String, JsonNode> metrics = new LinkedHashMap<>();
        for (String name : List.of("bookings.requests", "bookings.deduplication_hits",
                "bookings.serviceability_rejections", "batching.trips_formed", "batching.sla_rejections",
                "dispatch.offers", "dispatch.rejections", "batching.late_insertion", "bookings.cancellation_replans")) {
            metrics.put(name, request("GET", "/actuator/metrics/" + name, null, null, null, 200));
        }
        metrics.put("batching.duration", request("GET", "/actuator/metrics/batching.duration", null, null, null, 200));
        assertThat(metrics.get("batching.duration").path("measurements").get(0).path("value").asDouble()).isEqualTo(2);
        for (var expected : Map.of("bookings.requests", 8.0, "bookings.deduplication_hits", 1.0,
                "bookings.serviceability_rejections", 1.0, "batching.trips_formed", 4.0,
                "dispatch.offers", 2.0, "dispatch.rejections", 1.0,
                "batching.late_insertion", 1.0, "bookings.cancellation_replans", 1.0).entrySet()) {
            assertThat(metrics.get(expected.getKey()).path("measurements").get(0).path("value").asDouble())
                    .as(expected.getKey()).isEqualTo(expected.getValue());
        }
        assertThat(metrics.get("batching.sla_rejections").path("measurements").get(0).path("value").asDouble())
                .isGreaterThan(0);
        HttpResponse<String> prometheus = send("GET", "/actuator/prometheus", null, null, null);
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("bookings_deduplication_hits_total");
        Files.writeString(OUTPUT.resolve("prometheus.txt"), prometheus.body());
        stage("16-metrics", "GET /actuator/metrics/{name} and /actuator/prometheus", metrics);
        stage("17-final-state", "Read committed PostgreSQL state",
                Map.of("bookings", bookings.count(), "trips", readTrips().stream().map(this::snapshot).toList(),
                        "offers", offers.findAll(), "assertions", "PASS"));
        System.out.println("[DEMO] PASS: dedup, zone error, 4+1, SLA split, cool-off, acceptance, late insertion, isolated cancellation, metrics");
        if (Boolean.getBoolean("demo.hold")) {
            System.out.println("[DEMO] HTTP server remains at http://localhost:18080 ; Ctrl+C or python scripts/demo/demo.py stop ends it.");
            while (!Files.exists(OUTPUT.resolve("stop"))) Thread.sleep(500);
        }
    }

    private void seed() {
        for (int n = 1; n <= 8; n++) {
            employees.save(new Employee(id(n), "DEMO-" + n, "Demo Rider " + n, "rider" + n + "@example.invalid"));
        }
        zones.saveAndFlush(new ServiceableZone("Reviewer Demo", PICKUP.latitude(), PICKUP.longitude(), 5000, true));
        accounts.save(new UserAccount(id(90), "reviewer-admin", passwords.encode("demo-only-password"), Role.ADMIN, null));
        accounts.saveAndFlush(new UserAccount(id(91), "reviewer-rider", passwords.encode("demo-only-password"),
                Role.EMPLOYEE, id(1)));
        for (int n = 101; n <= 104; n++) {
            Driver driver = new Driver(id(n), switch (n) {
                case 101 -> "Nearest eligible";
                case 102 -> "Backup eligible";
                case 103 -> "Nearest but two seats";
                default -> "Offline driver";
            }, n == 103 ? 2 : 4);
            driver.updateLocation(n == 102 ? new GeoPoint(12.9800, 77.5946) : PICKUP);
            if (n != 104) driver.goOnline();
            drivers.saveAndFlush(driver);
        }
    }

    private String login(String username) throws Exception {
        return request("POST", "/auth/login", null, null,
                Map.of("username", username, "password", "demo-only-password"), 200).path("token").asText();
    }

    private JsonNode create(int employee, String token) throws Exception {
        return request("POST", "/api/v1/bookings", token, id(employee), body(PICKUP, DROPOFF), 201);
    }

    private JsonNode trigger() throws Exception {
        return request("POST", "/api/admin/batching/trigger", admin, null, null, 200);
    }

    private Object body(GeoPoint pickup, GeoPoint dropoff) {
        return Map.of("pickup", pickup, "dropoff", dropoff);
    }

    private HttpResponse<String> send(String method, String path, String token, UUID employee, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:18080" + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (employee != null) builder.header("X-Employee-Id", employee.toString());
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode request(String method, String path, String token, UUID employee, Object body, int status) throws Exception {
        HttpResponse<String> response = send(method, path, token, employee, body);
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }

    private List<Trip> readTrips() {
        return new TransactionTemplate(transactions).execute(status -> {
            List<Trip> result = new ArrayList<>(trips.findAll());
            result.forEach(t -> t.getStops().size());
            return result;
        });
    }

    private Trip readTrip(UUID key) {
        return new TransactionTemplate(transactions).execute(status -> {
            Trip trip = trips.findById(key).orElseThrow();
            trip.getStops().size();
            return trip;
        });
    }

    private Map<String, Object> snapshot(Trip trip) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", trip.getId());
        result.put("status", trip.getStatus());
        result.put("driverId", trip.getDriverId());
        result.put("vehicleId", trip.getVehicleId());
        result.put("createdAt", trip.getCreatedAt());
        result.put("updatedAt", trip.getUpdatedAt());
        result.put("windowStartTime", trip.getWindowStartTime());
        result.put("seats", 4);
        result.put("peakPassengers", trip.getPeakPassengers());
        result.put("riders", trip.getStops().stream().filter(s -> s.getType() == TripStopType.PICKUP)
                .map(s -> labels.get(s.getBookingId())).toList());
        result.put("stops", trip.getStops().stream().map(s -> Map.of("id", s.getId(), "bookingId", s.getBookingId(),
                "rider", labels.get(s.getBookingId()), "type", s.getType(), "sequence", s.getSequenceOrder(),
                "location", s.getLocation())).toList());
        return result;
    }

    private List<TripStop> packedRoute(List<Booking> riders) {
        List<TripStop> route = new ArrayList<>();
        for (Booking b : riders) route.add(new TripStop(UUID.randomUUID(), b.getId(), TripStopType.PICKUP, b.getPickupLocation()));
        for (Booking b : riders) route.add(new TripStop(UUID.randomUUID(), b.getId(), TripStopType.DROPOFF, b.getDropoffLocation()));
        return route;
    }

    private Map<UUID, Booking> bookingMap(List<Booking> riders) {
        Map<UUID, Booking> result = new LinkedHashMap<>();
        riders.forEach(b -> result.put(b.getId(), b));
        return result;
    }

    private static UUID id(int n) { return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n)); }

    private void stage(String name, String command, Object data) throws Exception {
        String value = json.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        Files.writeString(OUTPUT.resolve(name + ".json"), value + "\n");
        Files.writeString(OUTPUT.resolve("evidence.md"), "## " + name + "\n\nCommand: `" + command
                + "`\n\n```json\n" + value + "\n```\n\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("[DEMO] " + name + " verified -> target/demo/" + name + ".json");
    }
}
