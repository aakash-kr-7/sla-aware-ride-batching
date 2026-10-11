package com.ridebatching.dispatch.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.auth.*;
import com.ridebatching.booking.*;
import com.ridebatching.booking.dto.*;
import com.ridebatching.dispatch.*;
import com.ridebatching.driver.*;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.*;
import com.ridebatching.zone.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DispatchWorkflowHttpIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired UserAccountRepository accounts;
    @Autowired EmployeeRepository employees;
    @Autowired BookingRepository bookings;
    @Autowired ServiceableZoneRepository zones;
    @Autowired DriverRepository drivers;
    @Autowired DriverOfferRepository offers;
    @Autowired TripRepository trips;
    @Autowired RejectionCoolOffPolicy coolOff;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @MockBean(name = "batchingClock") Clock batchingClock;
    @MockBean(name = "dispatchClock") Clock dispatchClock;
    private HttpHeaders admin;
    private HttpHeaders employee;
    private HttpHeaders approver;
    private UUID employeeId;
    private Instant planningTime;
    private Driver near, backup;

    @BeforeEach void setup() {
        employeeId = employees.saveAndFlush(new Employee(UUID.randomUUID(), "FLOW", "Rider", "flow@example.invalid")).getId();
        zones.saveAndFlush(new ServiceableZone("Flow", 0, 0, 5000, true));
        admin = account("admin", Role.ADMIN, null);
        employee = account("rider", Role.EMPLOYEE, employeeId);
        employee.set("X-Employee-Id", employeeId.toString());
        approver = account("approver", Role.APPROVER, null);
        planningTime = Instant.now().plusSeconds(900);
        when(batchingClock.instant()).thenReturn(planningTime);
        when(dispatchClock.instant()).thenReturn(planningTime);
        near = driver(0);
        backup = driver(0.001);
    }

    @Test void bookingBatchRejectionCoolOffAcceptanceAndCancellationUseNormalHttpEntries() {
        BookingResponse booking = create();
        DispatchView initial = batch(booking.id());
        assertThat(initial.outcome()).isEqualTo(DispatchOutcome.OFFERED);
        assertThat(initial.peakPassengers()).isEqualTo(1);
        assertThat(initial.stops()).extracting(DispatchView.Stop::bookingId).containsOnly(booking.id());
        UUID first = initial.activeOfferId();
        assertThat(offers.findById(first).orElseThrow().getDriverId()).isEqualTo(near.getId());
        assertThat(post("/trips/" + initial.tripId() + "/advance").getBody().activeOfferId()).isEqualTo(first);
        DispatchView next = post("/offers/" + first + "/reject").getBody();
        assertThat(coolOff.isExcludedByCoolOff(near.getId(), initial.tripId(), planningTime)).isTrue();
        assertThat(next.attemptsUsed()).isEqualTo(2);
        assertThat(offers.findById(next.activeOfferId()).orElseThrow().getDriverId()).isEqualTo(backup.getId());
        assertThat(post("/offers/" + first + "/reject").getBody().activeOfferId()).isEqualTo(next.activeOfferId());
        DispatchView accepted = post("/offers/" + next.activeOfferId() + "/accept").getBody();
        assertThat(accepted.outcome()).isEqualTo(DispatchOutcome.ASSIGNED);
        assertThat(accepted.assignedDriverId()).isEqualTo(backup.getId());
        assertThat(drivers.findById(backup.getId()).orElseThrow().getActiveTripId()).isEqualTo(initial.tripId());
        assertThat(post("/offers/" + next.activeOfferId() + "/accept").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancel(booking.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(initial.tripId()).outcome()).isEqualTo(DispatchOutcome.CANCELLED);
        assertThat(drivers.findById(backup.getId()).orElseThrow().getActiveTripId()).isNull();
        assertThat(postError("/offers/" + next.activeOfferId() + "/accept").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void lateAcceptanceReturnsExpiryConflictAndPersistsTheNextOffer() {
        DispatchView initial = batch(create().id());
        // Use the advertised persisted deadline: PostgreSQL timestamps have microsecond precision.
        when(dispatchClock.instant()).thenReturn(initial.offers().get(0).expiresAt());
        var expired = postError("/offers/" + initial.activeOfferId() + "/accept");
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(expired.getBody().path("code").asText()).isEqualTo("OFFER_EXPIRED");
        DispatchView next = get(initial.tripId());
        assertThat(next.outcome()).isEqualTo(DispatchOutcome.OFFERED);
        assertThat(next.activeOfferId()).isNotEqualTo(initial.activeOfferId());
        assertThat(next.offers()).filteredOn(o -> o.id().equals(initial.activeOfferId()))
                .extracting(DispatchView.Offer::status).containsExactly(DriverOfferStatus.EXPIRED);
        assertThat(drivers.findById(near.getId()).orElseThrow().getActiveTripId()).isNull();
    }

    @Test void noDriverAndAllRejectionsAreVisibleWithoutUnboundedOffers() {
        near.goOffline(); backup.goOffline(); drivers.saveAllAndFlush(List.of(near, backup));
        DispatchView initial = batch(create().id());
        assertThat(initial.outcome()).isEqualTo(DispatchOutcome.NO_ELIGIBLE_DRIVER);
        List<Driver> online = drivers.findAll(); online.forEach(Driver::goOnline); drivers.saveAllAndFlush(online);
        assertThat(get(initial.tripId()).outcome()).isEqualTo(DispatchOutcome.READY_FOR_RETRY);
        DispatchView first = post("/trips/" + initial.tripId() + "/advance").getBody();
        DispatchView second = post("/offers/" + first.activeOfferId() + "/reject").getBody();
        DispatchView exhausted = post("/offers/" + second.activeOfferId() + "/reject").getBody();
        assertThat(exhausted.outcome()).isEqualTo(DispatchOutcome.DRIVERS_EXHAUSTED);
        for (int i = 0; i < 3; i++) assertThat(post("/trips/" + initial.tripId() + "/advance").getBody().activeOfferId()).isNull();
        assertThat(offers.count()).isEqualTo(2);
        assertThat(get(initial.tripId()).tripStatus()).isEqualTo(TripStatus.PLANNED);
    }

    @Test void concurrentHttpAcceptanceAndFinalRiderCancellationLeaveNoActiveAssignment() throws Exception {
        BookingResponse booking = create();
        DispatchView initial = batch(booking.id());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<JsonNode>>> workers = transaction.execute(status -> {
                jdbc.queryForObject("SELECT id FROM trips WHERE id=? FOR UPDATE", UUID.class, initial.tripId());
                var accept = executor.submit(() -> postError("/offers/" + initial.activeOfferId() + "/accept"));
                var cancel = executor.submit(() -> cancel(booking.id()));
                awaitTwoTripLockWaits();
                return List.of(accept, cancel);
            });
            assertThat(workers.get(0).get(10, TimeUnit.SECONDS).getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CONFLICT);
            assertThat(workers.get(1).get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            DispatchView end = get(initial.tripId());
            assertThat(end.outcome()).isEqualTo(DispatchOutcome.CANCELLED);
            assertThat(end.stops()).isEmpty();
            assertThat(end.activeOfferId()).isNull();
            assertThat(bookings.findById(booking.id()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(drivers.findById(near.getId()).orElseThrow().getActiveTripId()).isNull();
            assertThat(offers.findById(initial.activeOfferId()).orElseThrow().getStatus())
                    .isIn(DriverOfferStatus.ACCEPTED, DriverOfferStatus.EXPIRED);
            assertThat(postError("/offers/" + initial.activeOfferId() + "/accept").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void everyDispatchOperationRequiresAdminAndStoredOfferIdentity() {
        DispatchView initial = batch(create().id());
        List<String> gets = List.of("/trips", "/trips/" + initial.tripId());
        List<String> posts = List.of("/trips/" + initial.tripId() + "/advance", "/offers/" + initial.activeOfferId() + "/accept",
                "/offers/" + initial.activeOfferId() + "/reject");
        for (HttpHeaders denied : List.of(employee, approver, new HttpHeaders())) {
            for (String path : gets) assertThat(http.exchange("/api/admin/dispatch" + path, HttpMethod.GET,
                    new HttpEntity<>(denied), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            for (String path : posts) assertThat(http.exchange("/api/admin/dispatch" + path, HttpMethod.POST,
                    new HttpEntity<>(denied), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(offers.count()).isEqualTo(1);
        assertThat(get(initial.tripId()).tripStatus()).isEqualTo(TripStatus.PLANNED);
        assertThat(http.exchange("/api/admin/dispatch/trips?size=101", HttpMethod.GET, new HttpEntity<>(admin), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postError("/offers/" + UUID.randomUUID() + "/accept").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode schema = http.getForObject("/v3/api-docs", JsonNode.class);
        assertThat(schema.path("paths").path("/api/admin/dispatch/offers/{offerId}/accept").path("post").has("requestBody")).isFalse();
        JsonNode list = http.exchange("/api/admin/dispatch/trips", HttpMethod.GET, new HttpEntity<>(admin), JsonNode.class).getBody();
        assertThat(list.path("content").get(0).path("tripId").asText()).isEqualTo(initial.tripId().toString());
    }

    @Test void overlappingHttpAdvancesCreateOnlyOneOutstandingOffer() throws Exception {
        near.goOffline(); backup.goOffline(); drivers.saveAllAndFlush(List.of(near, backup));
        DispatchView initial = batch(create().id());
        List<Driver> online = drivers.findAll(); online.forEach(Driver::goOnline); drivers.saveAllAndFlush(online);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<DispatchView>>> workers = transaction.execute(status -> {
                jdbc.queryForObject("SELECT id FROM trips WHERE id=? FOR UPDATE", UUID.class, initial.tripId());
                var first = executor.submit(() -> post("/trips/" + initial.tripId() + "/advance"));
                var second = executor.submit(() -> post("/trips/" + initial.tripId() + "/advance"));
                awaitTwoTripLockWaits();
                return List.of(first, second);
            });
            UUID first = workers.get(0).get(10, TimeUnit.SECONDS).getBody().activeOfferId();
            assertThat(first).isNotNull();
            assertThat(workers.get(1).get(10, TimeUnit.SECONDS).getBody().activeOfferId()).isEqualTo(first);
            assertThat(offers.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
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
        fail("Both HTTP requests must reach real PostgreSQL trip lock waits");
    }
    private HttpHeaders account(String username, Role role, UUID employeeLink) {
        accounts.saveAndFlush(new UserAccount(UUID.randomUUID(), username, passwords.encode("workflow-test-password"), role, employeeLink));
        var response = http.postForEntity("/auth/login", Map.of("username", username, "password", "workflow-test-password"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(response.getBody().path("token").asText());
        return headers;
    }
    private Driver driver(double latitude) {
        Driver driver = new Driver(UUID.randomUUID(), "Workflow driver", 4);
        driver.updateLocation(new GeoPoint(latitude, 0)); driver.goOnline(); return drivers.saveAndFlush(driver);
    }
    private BookingResponse create() {
        var response = http.postForEntity("/api/v1/bookings", new HttpEntity<>(new BookingRequest(new GeoPointDto(0.0, 0.0),
                new GeoPointDto(0.01, 0.0)), employee), BookingResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED); return response.getBody();
    }
    private DispatchView batch(UUID bookingId) {
        var response = http.postForEntity("/api/admin/batching/trigger", new HttpEntity<>(admin), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("tripsFormed").asInt()).isEqualTo(1);
        UUID tripId = bookings.findById(bookingId).orElseThrow().getTripId();
        assertThat(tripId).isNotNull(); return get(tripId);
    }
    private DispatchView get(UUID tripId) {
        var response = http.exchange("/api/admin/dispatch/trips/" + tripId, HttpMethod.GET, new HttpEntity<>(admin), DispatchView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); return response.getBody();
    }
    private ResponseEntity<DispatchView> post(String path) {
        var response = http.postForEntity("/api/admin/dispatch" + path, new HttpEntity<>(admin), DispatchView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); return response;
    }
    private ResponseEntity<JsonNode> postError(String path) {
        return http.postForEntity("/api/admin/dispatch" + path, new HttpEntity<>(admin), JsonNode.class);
    }
    private ResponseEntity<JsonNode> cancel(UUID bookingId) {
        return http.exchange("/api/v1/bookings/" + bookingId, HttpMethod.DELETE, new HttpEntity<>(employee), JsonNode.class);
    }
}
