package com.ridebatching.dispatch.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.auth.*;
import com.ridebatching.batching.BatchingWindow;
import com.ridebatching.booking.*;
import com.ridebatching.dispatch.*;
import com.ridebatching.driver.*;
import com.ridebatching.geo.*;
import com.ridebatching.trip.*;
import com.ridebatching.zone.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssignmentTimingHttpIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T10:17:00Z");
    @Autowired TestRestTemplate http;
    @Autowired BookingRepository bookings;
    @Autowired EmployeeRepository employees;
    @Autowired DriverRepository drivers;
    @Autowired TripRepository trips;
    @Autowired DriverOfferRepository offers;
    @Autowired UserAccountRepository accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired ServiceableZoneRepository zones;
    @Autowired TravelTimeEstimator estimator;
    @Autowired TransactionTemplate transaction;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockBean(name = "batchingClock") Clock batchingClock;
    @MockBean(name = "dispatchClock") Clock dispatchClock;
    private HttpHeaders admin;
    private Driver driver;

    @BeforeEach void setup() {
        time(NOW);
        zones.saveAndFlush(new ServiceableZone("Timing", 0, 0, 1000, true));
        accounts.saveAndFlush(new UserAccount(UUID.randomUUID(), "timing-admin", passwords.encode("timing-test-password"), Role.ADMIN, null));
        var login = http.postForEntity("/auth/login", Map.of("username", "timing-admin", "password", "timing-test-password"), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        admin = new HttpHeaders(); admin.setBearerAuth(login.getBody().path("token").asText());
        driver = new Driver(UUID.randomUUID(), "Timing driver", 4); driver.goOnline(); drivers.saveAndFlush(driver);
    }

    @Test void unexpiredOfferCannotCommitAfterWaitingMakesApproachMissThePickupPromise() {
        position(new GeoPoint(.002, 0)); // 36 seconds' approach under the configured local model.
        Booking b = pending(new GeoPoint(0, 0), new GeoPoint(.01, 0), 60);
        DispatchView initial = batch(b);
        UUID offerId = initial.activeOfferId();
        assertThat(offerId).isNotNull();
        assertThat(offers.findById(offerId).orElseThrow().getExpiresAt()).isEqualTo(NOW.plusSeconds(120));
        time(NOW.plusSeconds(25)); // Offer is live, but first pickup is now +61, past the +60 promise.
        conflict(offerId, "PICKUP_SLA");
        assertThat(get(initial.tripId())).isEqualTo(initial);
        assertUnassigned(initial.tripId(), offerId);

    }

    @Test void acceptanceAllowsExactPickupDeadlineEqualityAndAnIdempotentRetryDoesNotRetimestamp() {
        position(new GeoPoint(.002, 0));
        Booking b = pending(new GeoPoint(0, 0), new GeoPoint(.01, 0), 60);
        DispatchView initial = batch(b);
        UUID offerId = initial.activeOfferId();
        time(NOW.plusSeconds(24)); // Exact pickup deadline equality is feasible.
        var accepted = post("/api/admin/dispatch/offers/" + offerId + "/accept");
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        var assigned = get(initial.tripId());
        assertThat(assigned.stops().get(0).estimatedArrivalAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(assigned.stops().get(1).estimatedArrivalAt()).isEqualTo(NOW.plusSeconds(60)
                .plus(estimator.estimateTravelTime(b.getPickupLocation(), b.getDropoffLocation())));
        assertThat(assigned.tripStatus()).isEqualTo(TripStatus.DISPATCHED);
        assertThat(drivers.findById(driver.getId()).orElseThrow().getActiveTripId()).isEqualTo(initial.tripId());
        time(NOW.plusSeconds(300));
        assertThat(post("/api/admin/dispatch/offers/" + offerId + "/accept").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(initial.tripId()).stops()).isEqualTo(assigned.stops()); // Retry is not a new assignment.
    }

    @Test void acceptanceReadsTheCurrentDriverPositionAndAdvanceRetiresAnInfeasibleOffer() {
        Booking b = pending(new GeoPoint(0, 0), new GeoPoint(.01, 0), 60);
        DispatchView initial = batch(b);
        position(new GeoPoint(.01, 0));
        conflict(initial.activeOfferId(), "PICKUP_SLA");
        assertUnassigned(initial.tripId(), initial.activeOfferId());
        assertThat(post("/api/admin/dispatch/trips/" + initial.tripId() + "/advance").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(offers.findById(initial.activeOfferId()).orElseThrow().getStatus()).isEqualTo(DriverOfferStatus.EXPIRED);
        assertThat(get(initial.tripId()).outcome()).isEqualTo(DispatchOutcome.DRIVERS_EXHAUSTED);
    }

    @Test void aDriverThatCannotApproachWithinThePromiseIsNotOfferedTheTrip() {
        position(new GeoPoint(.01, 0));
        DispatchView result = batch(pending(new GeoPoint(0, 0), new GeoPoint(.01, 0), 60));
        assertThat(result.outcome()).isEqualTo(DispatchOutcome.NO_ELIGIBLE_DRIVER);
        assertThat(result.activeOfferId()).isNull();
        assertThat(result.tripStatus()).isEqualTo(TripStatus.PLANNED);
        assertThat(offers.count()).isZero();
    }

    @Test void acceptanceRechecksDetourOnTheCurrentStoredRoute() {
        Booking a = pending(new GeoPoint(0, 0), new GeoPoint(.001, 0), 1800);
        Booking b = pending(new GeoPoint(0, 0), new GeoPoint(.01, 0), 1800);
        DispatchView initial = batch(a);
        assertThat(bookings.findById(b.getId()).orElseThrow().getTripId()).isEqualTo(initial.tripId());
        transaction.executeWithoutResult(status -> {
            Trip trip = trips.findByIdForUpdate(initial.tripId()).orElseThrow();
            Map<String, TripStop> stops = new HashMap<>();
            trip.getStops().forEach(s -> stops.put(s.getBookingId() + ":" + s.getType(), s));
            trip.assignRoute(List.of(stops.get(a.getId() + ":PICKUP"), stops.get(b.getId() + ":PICKUP"),
                    stops.get(b.getId() + ":DROPOFF"), stops.get(a.getId() + ":DROPOFF")));
        });
        conflict(initial.activeOfferId(), "RELATIVE_DETOUR");
        assertUnassigned(initial.tripId(), initial.activeOfferId());
    }

    @Test void cancellingTheFirstStopOfAnAcceptedTripRecomputesApproachFromCurrentDriverPosition() {
        Booking a = pending(new GeoPoint(0, 0), new GeoPoint(.01, 0), 1200);
        pending(new GeoPoint(.001, 0), new GeoPoint(.011, 0), 1200);
        DispatchView initial = batch(a);
        assertThat(post("/api/admin/dispatch/offers/" + initial.activeOfferId() + "/accept").getStatusCode()).isEqualTo(HttpStatus.OK);
        position(new GeoPoint(.005, 0)); time(NOW.plusSeconds(20));
        UUID first = initial.stops().get(0).bookingId();
        var owner = bookings.findById(first).orElseThrow().getEmployee().getId();
        HttpHeaders headers = new HttpHeaders(); headers.putAll(admin); headers.set("X-Employee-Id", owner.toString());
        assertThat(http.exchange("/api/v1/bookings/" + first, HttpMethod.DELETE, new HttpEntity<>(headers), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var revised = get(initial.tripId());
        assertThat(revised.stops()).hasSize(2);
        Instant expected = NOW.plusSeconds(20).plus(estimator.estimateApproachTime(new GeoPoint(.005, 0), revised.stops().get(0).location()));
        assertThat(revised.stops().get(0).estimatedArrivalAt()).isEqualTo(expected);
        assertThat(revised.tripStatus()).isEqualTo(TripStatus.DISPATCHED);
        assertThat(drivers.findById(driver.getId()).orElseThrow().getActiveTripId()).isEqualTo(initial.tripId());
    }

    @Test void cancellationCannotKeepAnAssignmentOverItsCurrentDriverCapacity() {
        Booking a = pending(new GeoPoint(0, 0), new GeoPoint(.01, 0), 1200);
        pending(new GeoPoint(0, 0), new GeoPoint(.01, 0), 1200);
        pending(new GeoPoint(0, 0), new GeoPoint(.01, 0), 1200);
        DispatchView initial = batch(a);
        assertThat(post("/api/admin/dispatch/offers/" + initial.activeOfferId() + "/accept").getStatusCode()).isEqualTo(HttpStatus.OK);
        DispatchView before = get(initial.tripId());
        jdbc.update("UPDATE drivers SET vehicle_capacity=1 WHERE id=?", driver.getId());
        var owner = bookings.findById(a.getId()).orElseThrow().getEmployee().getId();
        HttpHeaders headers = new HttpHeaders(); headers.putAll(admin); headers.set("X-Employee-Id", owner.toString());
        var reply = http.exchange("/api/v1/bookings/" + a.getId(), HttpMethod.DELETE, new HttpEntity<>(headers), JsonNode.class);
        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reply.getBody().path("message").asText()).contains("CAPACITY");
        assertThat(bookings.findById(a.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(get(initial.tripId())).isEqualTo(before);
        assertThat(drivers.findById(driver.getId()).orElseThrow().getActiveTripId()).isEqualTo(initial.tripId());
    }

    @Test void emptyRoutesCannotBeOfferedOrAcceptedEvenThroughLegacyOfferRecords() {
        Trip empty = trips.saveAndFlush(new Trip(UUID.randomUUID(), NOW, NOW));
        assertThat(post("/api/admin/dispatch/trips/" + empty.getId() + "/advance").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(empty.getId()).activeOfferId()).isNull();
        DriverOffer legacy = offers.saveAndFlush(new DriverOffer(UUID.randomUUID(), driver.getId(), empty.getId(), NOW, Duration.ofMinutes(2)));
        conflict(legacy.getId(), "ASSIGNMENT_EMPTY_ROUTE");
        assertUnassigned(empty.getId(), legacy.getId());
    }

    private void time(Instant now) { when(batchingClock.instant()).thenReturn(now); when(dispatchClock.instant()).thenReturn(now); }
    private void position(GeoPoint p) { driver = drivers.findById(driver.getId()).orElseThrow(); driver.updateLocation(p); driver = drivers.saveAndFlush(driver); }
    private Booking pending(GeoPoint pickup, GeoPoint drop, long remaining) {
        UUID employeeId = UUID.randomUUID();
        Employee rider = employees.saveAndFlush(new Employee(employeeId, employeeId.toString(), "Rider", employeeId + "@example.invalid"));
        Instant created = NOW.minusSeconds(1200);
        return bookings.saveAndFlush(new Booking(UUID.randomUUID(), rider, pickup, drop,
                BatchingWindow.forTime(created, Duration.ofMinutes(15)).start(), BookingStatus.PENDING,
                NOW.plusSeconds(remaining), created, UUID.randomUUID().toString()));
    }
    private DispatchView batch(Booking b) {
        assertThat(post("/api/admin/batching/trigger").getStatusCode()).isEqualTo(HttpStatus.OK);
        return get(bookings.findById(b.getId()).orElseThrow().getTripId());
    }
    private DispatchView get(UUID id) { return http.exchange("/api/admin/dispatch/trips/" + id, HttpMethod.GET, new HttpEntity<>(admin), DispatchView.class).getBody(); }
    private ResponseEntity<JsonNode> post(String path) { return http.postForEntity(path, new HttpEntity<>(admin), JsonNode.class); }
    private void conflict(UUID offer, String reason) {
        var reply = post("/api/admin/dispatch/offers/" + offer + "/accept");
        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reply.getBody().path("code").asText()).isEqualTo("ROUTE_INFEASIBLE");
        assertThat(reply.getBody().path("message").asText()).contains(reason);
    }
    private void assertUnassigned(UUID trip, UUID offer) {
        assertThat(trips.findById(trip).orElseThrow().getStatus()).isEqualTo(TripStatus.PLANNED);
        assertThat(offers.findById(offer).orElseThrow().getStatus()).isEqualTo(DriverOfferStatus.OFFERED);
        assertThat(drivers.findById(driver.getId()).orElseThrow().getActiveTripId()).isNull();
    }
}
