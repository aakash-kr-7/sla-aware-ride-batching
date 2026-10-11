package com.ridebatching.booking.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.auth.*;
import com.ridebatching.batching.BatchingWindow;
import com.ridebatching.booking.*;
import com.ridebatching.booking.dto.*;
import com.ridebatching.dispatch.*;
import com.ridebatching.driver.*;
import com.ridebatching.escalation.*;
import com.ridebatching.geo.*;
import com.ridebatching.trip.*;
import com.ridebatching.zone.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingLifecycleHttpIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T10:17:00Z");
    @Autowired TestRestTemplate http;
    @Autowired EmployeeRepository employees;
    @Autowired BookingRepository bookings;
    @Autowired TripRepository trips;
    @Autowired DriverRepository drivers;
    @Autowired UserAccountRepository accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired ServiceableZoneRepository zones;
    @Autowired SlaEscalationRepository issues;
    @Autowired TravelTimeEstimator estimator;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @MockBean(name = "batchingClock") Clock clock;
    @MockBean(name = "dispatchClock") Clock dispatchClock;
    private HttpHeaders admin, rider, approver;
    private Employee employee;
    private Driver driver;

    @BeforeEach void seed() {
        when(clock.instant()).thenReturn(NOW); when(dispatchClock.instant()).thenReturn(NOW);
        employee = employee();
        admin = login("admin", Role.ADMIN, null);
        rider = login("rider", Role.EMPLOYEE, employee.getId());
        rider.set("X-Employee-Id", employee.getId().toString());
        approver = login("approver", Role.APPROVER, null);
        zones.saveAllAndFlush(List.of(new ServiceableZone("First", 0, 0, 1000, true),
                new ServiceableZone("Unrelated", 0.03, 0, 1000, true)));
        driver = new Driver(UUID.randomUUID(), "Execution driver", 4);
        driver.goOnline(); drivers.saveAndFlush(driver);
    }

    @Test void normalHttpCreationPlanningAcceptancePickupAndDropoffCompleteTheTrip() {
        var created = http.postForEntity("/api/v1/bookings", new HttpEntity<>(new BookingRequest(
                new GeoPointDto(0.0, 0.0), new GeoPointDto(0.01, 0.0)), rider), BookingResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = created.getBody().id();
        assertThat(created.getBody().status()).isEqualTo(BookingStatus.PENDING);
        conflict(id, "pickup");
        Instant close = BatchingWindow.forTime(created.getBody().createdAt(), Duration.ofMinutes(15)).end();
        when(clock.instant()).thenReturn(close); when(dispatchClock.instant()).thenReturn(close);
        batch();
        DispatchView planned = trip(id);
        assertThat(bookings.findById(id).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(planned.tripStatus()).isEqualTo(TripStatus.PLANNED);
        assertThat(planned.assignedDriverId()).isNull();
        assertThat(planned.stops()).allSatisfy(s -> assertThat(s.estimatedArrivalAt()).isNotNull());
        conflict(id, "pickup"); conflict(id, "dropoff");
        accept(planned.activeOfferId());
        assertThat(progress(id, "pickup", admin).getBody().path("status").asText()).isEqualTo("PICKED_UP");
        assertThat(trip(id).tripStatus()).isEqualTo(TripStatus.IN_PROGRESS);
        assertThat(drivers.findById(driver.getId()).orElseThrow().getActiveTripId()).isEqualTo(planned.tripId());
        conflict(id, "pickup");
        assertThat(cancel(id).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(progress(id, "dropoff", admin).getBody().path("status").asText()).isEqualTo("DROPPED");
        assertThat(trip(id).tripStatus()).isEqualTo(TripStatus.COMPLETED);
        assertThat(trip(id).outcome()).isEqualTo(DispatchOutcome.COMPLETED);
        assertThat(drivers.findById(driver.getId()).orElseThrow().getActiveTripId()).isNull();
        conflict(id, "dropoff"); conflict(id, "pickup");
        assertThat(cancel(id).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void routeOrderAndWholeTripCancellationBoundaryAreEnforced() {
        Booking a = pending(employee, 0, 1200), b = pending(employee(), 0, 1200);
        batch(); DispatchView route = trip(a.getId());
        assertThat(bookings.findById(b.getId()).orElseThrow().getTripId()).isEqualTo(route.tripId());
        accept(route.activeOfferId());
        List<DispatchView.Stop> stops = route.stops();
        assertThat(stops.get(0).type()).isEqualTo(TripStopType.PICKUP);
        UUID first = stops.get(0).bookingId();
        UUID waiting = first.equals(a.getId()) ? b.getId() : a.getId();
        conflict(waiting, "pickup"); conflict(first, "dropoff");
        assertThat(progress(first, "pickup", admin).getStatusCode()).isEqualTo(HttpStatus.OK);
        DispatchView before = trip(a.getId());
        assertThat(bookings.findById(waiting).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        var denied = cancel(waiting);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(denied.getBody().path("code").asText()).isEqualTo("STATE_CONFLICT");
        assertThat(trip(a.getId())).isEqualTo(before);
        for (int i = 1; i < stops.size(); i++) {
            var stop = stops.get(i);
            assertThat(progress(stop.bookingId(), stop.type() == TripStopType.PICKUP ? "pickup" : "dropoff", admin)
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(trip(a.getId()).tripStatus()).isEqualTo(TripStatus.COMPLETED);
        assertThat(drivers.findById(driver.getId()).orElseThrow().getActiveTripId()).isNull();
    }

    @Test void cancellationRecomputesRemainingArrivalsAndLeavesUnrelatedTimingUntouched() {
        Booking a = pending(employee, 0, 1200); pending(employee(), 0, 1200);
        Booking unrelated = pending(employee(), 0.03, 1200);
        batch(); DispatchView before = trip(a.getId()), otherBefore = trip(unrelated.getId());
        Instant otherUpdated = trips.findById(otherBefore.tripId()).orElseThrow().getUpdatedAt();
        UUID cancelled = before.stops().get(0).bookingId();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertThat(cancel(cancelled).getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID remaining = before.stops().stream().map(DispatchView.Stop::bookingId).filter(id -> !id.equals(cancelled)).findFirst().orElseThrow();
        DispatchView after = trip(remaining);
        assertThat(after.stops()).hasSize(2);
        assertThat(after.stops()).extracting(DispatchView.Stop::sequence).containsExactly(1, 2);
        assertThat(after.stops().get(0).estimatedArrivalAt()).isEqualTo(NOW.plusSeconds(60));
        Instant drop = NOW.plusSeconds(60).plus(estimator.estimateTravelTime(after.stops().get(0).location(), after.stops().get(1).location()));
        assertThat(after.stops().get(1).estimatedArrivalAt()).isEqualTo(drop);
        assertThat(trip(unrelated.getId())).isEqualTo(otherBefore);
        assertThat(trips.findById(otherBefore.tripId()).orElseThrow().getUpdatedAt()).isEqualTo(otherUpdated);
        DispatchView stable = after;
        assertThat(cancel(cancelled).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(trip(remaining)).isEqualTo(stable);
    }

    @Test void delayedCancellationFlagsRemainingSlaRiskOnceAndResolvingCancellationPreservesHistory() {
        Booking a = pending(employee, 0, 1200); pending(employee(), 0, 1200); pending(employee(), 0, 1200);
        batch(); DispatchView initial = trip(a.getId());
        when(clock.instant()).thenReturn(NOW.plusSeconds(1201));
        UUID first = initial.stops().get(0).bookingId();
        assertThat(cancel(first).getStatusCode()).isEqualTo(HttpStatus.OK);
        DispatchView reduced = getTrip(initial.tripId());
        UUID second = reduced.stops().get(0).bookingId();
        UUID remaining = reduced.stops().stream().map(DispatchView.Stop::bookingId).filter(id -> !id.equals(second)).findFirst().orElseThrow();
        SlaEscalation original = issues.findByStatus(SlaEscalationStatus.OPEN).stream()
                .filter(e -> e.getReferenceId().equals(remaining)).findFirst().orElseThrow();
        assertThat(cancel(second).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issues.findByStatus(SlaEscalationStatus.OPEN)).singleElement().satisfies(e -> {
            assertThat(e.getId()).isEqualTo(original.getId());
            assertThat(e.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
            assertThat(e.getReason()).isEqualTo(BookingSlaEscalations.ROUTE_AT_RISK);
        });
        assertThat(issues.findByStatus(SlaEscalationStatus.RESOLVED)).hasSize(1);
        assertThat(cancel(remaining).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issues.findByStatus(SlaEscalationStatus.OPEN)).isEmpty();
        assertThat(issues.count()).isEqualTo(2);
    }

    @Test void missedSoloPromiseIsVisibleInApproverApiAndCancellationClosesTheIssue() {
        Booking expired = pending(employee, 0, -1);
        batch(); batch();
        var open = http.exchange("/api/v1/escalations/open", HttpMethod.GET, new HttpEntity<>(approver), JsonNode.class);
        assertThat(open.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(open.getBody().size()).isEqualTo(1);
        assertThat(open.getBody().get(0).path("referenceId").asText()).isEqualTo(expired.getId().toString());
        assertThat(cancel(expired.getId()).getStatusCode()).isEqualTo(HttpStatus.OK);
        batch();
        assertThat(issues.findByStatus(SlaEscalationStatus.OPEN)).isEmpty();
        assertThat(issues.findByStatus(SlaEscalationStatus.RESOLVED)).hasSize(1);
    }

    @Test void executionEndpointsAreAdminOnlyAndCannotReviveCancelledBookings() {
        Booking b = pending(employee, 0, 1200);
        for (HttpHeaders denied : List.of(rider, approver, new HttpHeaders())) {
            assertThat(progress(b.getId(), "pickup", denied).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(progress(b.getId(), "dropoff", denied).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(cancel(b.getId()).getStatusCode()).isEqualTo(HttpStatus.OK);
        conflict(b.getId(), "pickup"); conflict(b.getId(), "dropoff");
        assertThat(progress(UUID.randomUUID(), "pickup", admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void firstPickupAndAnotherRidersCancellationSerializeAtTheTripBoundary() throws Exception {
        Booking a = pending(employee, 0, 1200), b = pending(employee(), 0, 1200);
        batch(); DispatchView route = trip(a.getId()); accept(route.activeOfferId());
        UUID first = route.stops().get(0).bookingId();
        UUID waiting = first.equals(a.getId()) ? b.getId() : a.getId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var workers = transaction.execute(status -> {
                jdbc.queryForObject("SELECT id FROM trips WHERE id=? FOR UPDATE", UUID.class, route.tripId());
                var pickup = pool.submit(() -> progress(first, "pickup", admin));
                var cancellation = pool.submit(() -> cancel(waiting));
                awaitTwoTripLockWaits();
                return List.of(pickup, cancellation);
            });
            assertThat(workers.get(0).get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            HttpStatusCode cancellation = workers.get(1).get(10, TimeUnit.SECONDS).getStatusCode();
            assertThat(cancellation).isIn(HttpStatus.OK, HttpStatus.CONFLICT);
            DispatchView end = trip(first);
            assertThat(end.tripStatus()).isEqualTo(TripStatus.IN_PROGRESS);
            assertThat(bookings.findById(first).orElseThrow().getStatus()).isEqualTo(BookingStatus.PICKED_UP);
            assertThat(bookings.findById(waiting).orElseThrow().getStatus()).isEqualTo(
                    cancellation.equals(HttpStatus.OK) ? BookingStatus.CANCELLED : BookingStatus.CONFIRMED);
            assertThat(end.stops().stream().anyMatch(stop -> stop.bookingId().equals(waiting)))
                    .isEqualTo(cancellation.equals(HttpStatus.CONFLICT));
            assertThat(drivers.findById(driver.getId()).orElseThrow().getActiveTripId()).isEqualTo(route.tripId());
            assertThat(cancel(first).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    private void awaitTwoTripLockWaits() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            int waits = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                    AND wait_event_type='Lock' AND query ILIKE '%trips%'
                    """, Integer.class);
            if (waits >= 2) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        fail("Pickup and cancellation must both reach real PostgreSQL trip lock waits");
    }

    private HttpHeaders login(String name, Role role, UUID employeeId) {
        accounts.saveAndFlush(new UserAccount(UUID.randomUUID(), name, passwords.encode("lifecycle-test-password"), role, employeeId));
        var response = http.postForEntity("/auth/login", Map.of("username", name, "password", "lifecycle-test-password"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(response.getBody().path("token").asText()); return headers;
    }
    private Employee employee() {
        UUID id = UUID.randomUUID(); return employees.saveAndFlush(new Employee(id, id.toString(), "Rider", id + "@example.invalid"));
    }
    private Booking pending(Employee rider, double pickupLatitude, long remaining) {
        Instant created = NOW.minusSeconds(1200);
        return bookings.saveAndFlush(new Booking(UUID.randomUUID(), rider, new GeoPoint(pickupLatitude, 0), new GeoPoint(pickupLatitude + 0.01, 0),
                BatchingWindow.forTime(created, Duration.ofMinutes(15)).start(), BookingStatus.PENDING, NOW.plusSeconds(remaining), created, UUID.randomUUID().toString()));
    }
    private void batch() {
        assertThat(http.postForEntity("/api/admin/batching/trigger", new HttpEntity<>(admin), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    private DispatchView trip(UUID bookingId) { return getTrip(bookings.findById(bookingId).orElseThrow().getTripId()); }
    private DispatchView getTrip(UUID id) {
        var response = http.exchange("/api/admin/dispatch/trips/" + id, HttpMethod.GET, new HttpEntity<>(admin), DispatchView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); return response.getBody();
    }
    private void accept(UUID id) {
        assertThat(http.postForEntity("/api/admin/dispatch/offers/" + id + "/accept", new HttpEntity<>(admin), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    private ResponseEntity<JsonNode> progress(UUID id, String action, HttpHeaders headers) {
        return http.postForEntity("/api/admin/bookings/" + id + "/" + action, new HttpEntity<>(headers), JsonNode.class);
    }
    private void conflict(UUID id, String action) {
        var response = progress(id, action, admin);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().path("code").asText()).isEqualTo("STATE_CONFLICT");
    }
    private ResponseEntity<JsonNode> cancel(UUID id) {
        HttpHeaders headers = new HttpHeaders(); headers.putAll(admin);
        headers.set("X-Employee-Id", bookings.findById(id).orElseThrow().getEmployee().getId().toString());
        return http.exchange("/api/v1/bookings/" + id, HttpMethod.DELETE, new HttpEntity<>(headers), JsonNode.class);
    }
}
