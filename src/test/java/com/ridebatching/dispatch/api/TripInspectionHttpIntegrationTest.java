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
import com.ridebatching.zone.ServiceableZone;
import com.ridebatching.zone.ServiceableZoneRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TripInspectionHttpIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T10:17:00Z");
    private static final GeoPoint PICKUP = new GeoPoint(0, 0), DROP = new GeoPoint(.02, 0);
    @Autowired TestRestTemplate http;
    @Autowired BookingRepository bookings;
    @Autowired EmployeeRepository employees;
    @Autowired DriverRepository drivers;
    @Autowired TripRepository trips;
    @Autowired UserAccountRepository accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired ServiceableZoneRepository zones;
    @Autowired TravelTimeEstimator estimator;
    @Autowired TransactionTemplate transaction;
    @Autowired MeterRegistry metrics;
    @MockBean(name = "batchingClock") Clock batchingClock;
    @MockBean(name = "dispatchClock") Clock dispatchClock;
    private HttpHeaders admin;
    private Driver driver;

    @BeforeEach void setup() {
        time(NOW);
        zones.saveAndFlush(new ServiceableZone("Inspection", 0, 0, 5000, true));
        admin = login("inspection-admin", Role.ADMIN, null);
        driver = new Driver(UUID.randomUUID(), "Six-seat driver", 6);
        driver.updateLocation(new GeoPoint(.001, 0)); driver.goOnline(); drivers.saveAndFlush(driver);
    }

    @Test void actualBatchResultsExposeFourPlusOneGroupingCapacityAndEveryPickupPromise() {
        List<Booking> requests = fiveRequests();
        trigger();
        UUID sharedId = tripId(requests.get(0)), soloId = tripId(requests.get(4));
        assertThat(sharedId).isNotEqualTo(soloId);
        DispatchView shared = get(sharedId), solo = get(soloId);
        assertThat(shared.tripStatus()).isEqualTo(TripStatus.PLANNED);
        assertThat(shared.assignedDriverId()).isNull();
        assertThat(shared.planningCapacity()).isEqualTo(4); // Planning does not use the offered driver's six seats.
        assertThat(shared.peakPassengers()).isEqualTo(4);
        assertThat(shared.bookingIds()).containsExactlyInAnyOrderElementsOf(requests.subList(0, 4).stream().map(Booking::getId).toList());
        assertThat(shared.bookingIds()).containsExactlyElementsOf(shared.stops().stream().map(DispatchView.Stop::bookingId).distinct().toList());
        assertThat(shared.stops()).hasSize(8).extracting(DispatchView.Stop::sequence).containsExactly(1,2,3,4,5,6,7,8);
        assertThat(solo.bookingIds()).containsExactly(requests.get(4).getId());
        assertThat(solo.peakPassengers()).isEqualTo(1);
        for (Booking booking : requests.subList(0, 4)) {
            var pair = shared.stops().stream().filter(s -> s.bookingId().equals(booking.getId())).toList();
            assertThat(pair).extracting(DispatchView.Stop::type).containsExactly(TripStopType.PICKUP, TripStopType.DROPOFF);
            assertThat(pair.get(0).estimatedArrivalAt()).isBeforeOrEqualTo(booking.getPickupSlaDeadline());
            assertThat(pair.get(0).pickupSlaDeadline()).isEqualTo(booking.getPickupSlaDeadline());
            assertThat(pair.get(0).withinPickupSla()).isTrue();
            assertThat(pair.get(1).pickupSlaDeadline()).isNull();
            assertThat(pair.get(1).withinPickupSla()).isNull();
        }
        JsonNode page = response("/api/admin/dispatch/trips", admin).getBody();
        assertThat(page.path("content")).hasSize(2);
        assertThat(page.path("content").get(0).path("planningCapacity").asInt()).isEqualTo(4);
        assertThat(page.path("content").get(0).has("bookingIds")).isTrue();
    }

    @Test void acceptanceExposesDriverAssociationAndRefreshedApproachEstimates() {
        Booking booking = pending(1, PICKUP, DROP, 400);
        trigger(); DispatchView planned = get(tripId(booking));
        assertThat(planned.stops().get(0).estimatedArrivalAt()).isEqualTo(NOW);
        var accepted = http.postForEntity("/api/admin/dispatch/offers/" + planned.activeOfferId() + "/accept",
                new HttpEntity<>(admin), DispatchView.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        DispatchView assigned = get(planned.tripId());
        assertThat(assigned.tripStatus()).isEqualTo(TripStatus.DISPATCHED);
        assertThat(assigned.assignedDriverId()).isEqualTo(driver.getId());
        assertThat(assigned.bookingIds()).containsExactly(booking.getId());
        assertThat(assigned.stops().get(0).estimatedArrivalAt()).isEqualTo(NOW.plus(estimator.estimateApproachTime(driver.getLocation(), PICKUP)));
        assertThat(assigned.stops().get(0).pickupSlaDeadline()).isEqualTo(NOW.plusSeconds(400));
        assertThat(assigned.stops().get(0).withinPickupSla()).isTrue();
    }

    @Test void cancellationRemovesTheRiderFromInspectionAndRetainsTheUnrelatedResponse() {
        List<Booking> requests = fiveRequests(); trigger();
        DispatchView unrelated = get(tripId(requests.get(4)));
        HttpHeaders owner = new HttpHeaders(); owner.putAll(admin);
        owner.set("X-Employee-Id", requests.get(0).getEmployee().getId().toString());
        assertThat(http.exchange("/api/v1/bookings/" + requests.get(0).getId(), HttpMethod.DELETE,
                new HttpEntity<>(owner), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        DispatchView affected = get(tripId(requests.get(0)));
        assertThat(affected.bookingIds()).doesNotContain(requests.get(0).getId()).hasSize(3);
        assertThat(affected.stops()).hasSize(6);
        assertThat(affected.peakPassengers()).isEqualTo(3);
        assertThat(get(unrelated.tripId())).isEqualTo(unrelated);
    }

    @Test void anUnknownLegacyEstimateIsNotReportedAsMeetingSlaAndReadsDoNotRetimestamp() {
        Booking booking = pending(1, PICKUP, DROP, 400); trigger();
        UUID id = tripId(booking);
        transaction.executeWithoutResult(s -> {
            Trip trip = trips.findById(id).orElseThrow();
            // Import the same valid route with legacy stops that have no saved estimates.
            trip.assignRoute(trip.getStops().stream().map(stop -> new TripStop(UUID.randomUUID(),
                    stop.getBookingId(), stop.getType(), stop.getLocation())).toList());
        });
        DispatchView before = get(id);
        assertThat(before.stops().get(0).estimatedArrivalAt()).isNull();
        assertThat(before.stops().get(0).withinPickupSla()).isNull();
        assertThat(before.stops().get(0).pickupSlaDeadline()).isEqualTo(booking.getPickupSlaDeadline());
        time(NOW.plusSeconds(600));
        assertThat(get(id).stops()).isEqualTo(before.stops());
    }

    @Test void inspectionRequiresAnAdminAndSwaggerDescribesTheAddedFields() {
        Booking booking = pending(1, PICKUP, DROP, 400); trigger();
        UUID id = tripId(booking);
        for (HttpHeaders denied : List.of(login("inspection-employee", Role.EMPLOYEE, booking.getEmployee().getId()),
                login("inspection-approver", Role.APPROVER, null), new HttpHeaders())) {
            assertThat(response("/api/admin/dispatch/trips/" + id, denied).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response("/api/admin/dispatch/trips", denied).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response("/api/admin/dispatch/trips/" + id + "?candidateBookingId=" + UUID.randomUUID(), denied).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        JsonNode schema = http.getForObject("/v3/api-docs", JsonNode.class).path("components").path("schemas");
        assertThat(schema.path("DispatchView").path("properties").has("planningCapacity")).isTrue();
        assertThat(schema.path("DispatchView").path("properties").has("bookingIds")).isTrue();
        assertThat(schema.path("Stop").path("properties").has("pickupSlaDeadline")).isTrue();
        assertThat(schema.path("Stop").path("properties").has("withinPickupSla")).isTrue();
        assertThat(schema.path("DispatchView").path("properties").has("insertionCheck")).isTrue();
        assertThat(schema.path("RejectionReason").path("properties").has("estimatedPickupAt")).isTrue();
        assertThat(response("/api/admin/dispatch/trips/" + UUID.randomUUID(), admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void rejectedSharingExplainsCapacityAndSlaWithoutChangingRoutesAssignmentsOrCounters() {
        List<Booking> requests = fiveRequests(); trigger();
        UUID sharedId = tripId(requests.get(0)), soloId = tripId(requests.get(4));
        DispatchView before = get(sharedId), soloBefore = get(soloId);
        Map<String, Double> countersBefore = counters();
        InsertionInspection check = inspect(sharedId, requests.get(4).getId());
        assertThat(check.feasible()).isFalse();
        assertThat(check.proposedStops()).isEmpty();
        assertThat(check.rejections()).extracting(InsertionInspection.RejectionReason::constraintCode).contains("CAPACITY", "PICKUP_SLA");
        var capacity = check.rejections().stream().filter(r -> r.constraintCode().equals("CAPACITY")).findFirst().orElseThrow();
        assertThat(capacity.examplePeakPassengers()).isGreaterThan(before.planningCapacity());
        var sla = check.rejections().stream().filter(r -> r.constraintCode().equals("PICKUP_SLA")).findFirst().orElseThrow();
        assertThat(sla.estimatedPickupAt()).isAfter(sla.pickupSlaDeadline());
        assertThat(sla.affectedBooking()).isIn(requests.stream().map(Booking::getId).toArray());
        assertThat(check.alternativesEvaluated()).isEqualTo(45); // All 9*10/2 insertion pairs fail.
        assertThat(check.rejections().stream().mapToInt(InsertionInspection.RejectionReason::alternativesRejected).sum()).isEqualTo(45);
        assertThat(get(sharedId)).isEqualTo(before);
        assertThat(get(soloId)).isEqualTo(soloBefore);
        assertThat(tripId(requests.get(4))).isEqualTo(soloId);
        assertThat(counters()).isEqualTo(countersBefore);
    }

    @Test void twoUrgentRequestsExposeSlaRejectionDespiteFreeSeats() {
        Booking a = pending(1, PICKUP, DROP, 90), b = pending(2, PICKUP, DROP, 90);
        trigger(); assertThat(tripId(a)).isNotEqualTo(tripId(b));
        InsertionInspection check = inspect(tripId(a), b.getId());
        assertThat(check.feasible()).isFalse();
        assertThat(check.alternativesEvaluated()).isEqualTo(6);
        assertThat(check.rejections()).singleElement().satisfies(reason -> {
            assertThat(reason.constraintCode()).isEqualTo("PICKUP_SLA");
            assertThat(reason.examplePeakPassengers()).isLessThanOrEqualTo(2);
            assertThat(reason.estimatedPickupAt()).isEqualTo(NOW.plusSeconds(120)); // Closest miss, rather than an hours-late sequential route.
            assertThat(reason.pickupSlaDeadline()).isEqualTo(NOW.plusSeconds(90));
        });
    }

    @Test void aFeasiblePreviewDoesNotAssignThePendingCandidateAndUsesCurrentTimeNotDecisionHistory() {
        Booking a = pending(1, PICKUP, DROP, 400); trigger();
        Booking b = pending(2, PICKUP, DROP, 400);
        DispatchView before = get(tripId(a));
        InsertionInspection check = inspect(tripId(a), b.getId());
        assertThat(check.feasible()).isTrue();
        assertThat(check.timingBasis()).isEqualTo("UNASSIGNED_CURRENT_TIME");
        assertThat(check.evaluatedAt()).isEqualTo(NOW);
        assertThat(check.proposedStops()).hasSize(4).extracting(DispatchView.Stop::sequence).containsExactly(1,2,3,4);
        assertThat(check.proposedStops().stream().filter(s -> s.type() == TripStopType.PICKUP)).allMatch(DispatchView.Stop::withinPickupSla);
        assertThat(get(tripId(a))).isEqualTo(before);
        assertThat(bookings.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(tripId(b)).isNull();
        time(NOW.plusSeconds(401));
        InsertionInspection later = inspect(tripId(a), b.getId());
        assertThat(later.feasible()).isFalse();
        assertThat(later.routeStartTime()).isEqualTo(NOW.plusSeconds(401));
        assertThat(later.rejections()).extracting(InsertionInspection.RejectionReason::constraintCode).containsExactly("PICKUP_SLA");
        assertThat(get(tripId(a)).stops()).isEqualTo(before.stops());
    }

    @Test void inspectionShowsRejectedCheaperAlternativesAlongsideTheCostlierFeasibleRoute() {
        Booking a = pending(1, new GeoPoint(0, 0), new GeoPoint(0, .11), 360); trigger();
        Booking b = pending(2, new GeoPoint(0, .01), new GeoPoint(0, .1), 60);
        DispatchView before = get(tripId(a));
        InsertionInspection check = inspect(tripId(a), b.getId());
        assertThat(check.feasible()).isTrue();
        assertThat(check.rejections()).extracting(InsertionInspection.RejectionReason::constraintCode).contains("PICKUP_SLA");
        assertThat(check.proposedStops()).extracting(DispatchView.Stop::bookingId).containsExactly(b.getId(), a.getId(), b.getId(), a.getId());
        assertThat(check.proposedStops().get(0).estimatedArrivalAt()).isEqualTo(NOW);
        assertThat(check.proposedStops().get(1).estimatedArrivalAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(check.proposedStops().get(1).withinPickupSla()).isTrue();
        assertThat(get(tripId(a))).isEqualTo(before);
    }

    @Test void comparisonRejectsMissingAlreadyGroupedCancelledOrAssignedTripInputs() {
        List<Booking> requests = fiveRequests(); trigger();
        UUID sharedId = tripId(requests.get(0));
        assertThat(comparisonResponse(sharedId, requests.get(0).getId()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(comparisonResponse(sharedId, UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        HttpHeaders owner = new HttpHeaders(); owner.putAll(admin);
        owner.set("X-Employee-Id", requests.get(4).getEmployee().getId().toString());
        assertThat(http.exchange("/api/v1/bookings/" + requests.get(4).getId(), HttpMethod.DELETE, new HttpEntity<>(owner), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(comparisonResponse(sharedId, requests.get(4).getId()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        DispatchView planned = get(sharedId);
        assertThat(http.postForEntity("/api/admin/dispatch/offers/" + planned.activeOfferId() + "/accept", new HttpEntity<>(admin), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        Booking pending = pending(6, PICKUP, DROP, 400);
        var assigned = comparisonResponse(sharedId, pending.getId());
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(assigned.getBody().path("code").asText()).isEqualTo("STATE_CONFLICT");
    }

    @Test void detourExamplesExposeTheHarmedRiderAndDurationsWithoutHidingAFeasibleAlternative() {
        Booking shortRide = pending(1, PICKUP, new GeoPoint(.001, 0), 400); trigger();
        Booking longRide = pending(2, PICKUP, DROP, 400);
        InsertionInspection check = inspect(tripId(shortRide), longRide.getId());
        assertThat(check.feasible()).isTrue();
        var detour = check.rejections().stream().filter(r -> r.constraintCode().equals("RELATIVE_DETOUR")).findFirst().orElseThrow();
        assertThat(detour.affectedBooking()).isEqualTo(shortRide.getId());
        assertThat(detour.directTravelSeconds()).isEqualTo(estimator.estimateTravelTime(PICKUP, shortRide.getDropoffLocation()).getSeconds());
        assertThat(detour.sharedTravelSeconds()).isGreaterThan(2 * detour.directTravelSeconds());
        assertThat(check.proposedStops()).hasSize(4);
        assertThat(tripId(longRide)).isNull();
    }

    private InsertionInspection inspect(UUID trip, UUID candidate) {
        var result = http.exchange("/api/admin/dispatch/trips/" + trip + "?candidateBookingId=" + candidate,
                HttpMethod.GET, new HttpEntity<>(admin), DispatchView.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        return result.getBody().insertionCheck();
    }
    private ResponseEntity<JsonNode> comparisonResponse(UUID trip, UUID candidate) {
        return response("/api/admin/dispatch/trips/" + trip + "?candidateBookingId=" + candidate, admin);
    }
    private Map<String, Double> counters() {
        Map<String, Double> result = new TreeMap<>();
        for (String name : List.of("batching.sla_rejections", "batching.detour_rejections", "dispatch.offers", "dispatch.acceptances")) {
            var counter = metrics.find(name).counter(); result.put(name, counter == null ? 0 : counter.count());
        }
        return result;
    }

    private void time(Instant now) { when(batchingClock.instant()).thenReturn(now); when(dispatchClock.instant()).thenReturn(now); }
    private List<Booking> fiveRequests() {
        List<Booking> result = new ArrayList<>();
        for (int i = 1; i <= 5; i++) result.add(pending(i, PICKUP, DROP, 400));
        return result;
    }
    private Booking pending(long id, GeoPoint pickup, GeoPoint drop, long seconds) {
        UUID employeeId = UUID.randomUUID();
        Employee rider = employees.saveAndFlush(new Employee(employeeId, employeeId.toString(), "Rider", employeeId + "@example.invalid"));
        Instant created = NOW.minusSeconds(1200);
        return bookings.saveAndFlush(new Booking(new UUID(0, id), rider, pickup, drop,
                BatchingWindow.forTime(created, Duration.ofMinutes(15)).start(), BookingStatus.PENDING,
                NOW.plusSeconds(seconds), created, "inspection-" + id));
    }
    private UUID tripId(Booking b) { return bookings.findById(b.getId()).orElseThrow().getTripId(); }
    private void trigger() {
        assertThat(http.postForEntity("/api/admin/batching/trigger", new HttpEntity<>(admin), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    private DispatchView get(UUID id) {
        var response = http.exchange("/api/admin/dispatch/trips/" + id, HttpMethod.GET, new HttpEntity<>(admin), DispatchView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); return response.getBody();
    }
    private ResponseEntity<JsonNode> response(String path, HttpHeaders headers) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }
    private HttpHeaders login(String username, Role role, UUID employee) {
        accounts.saveAndFlush(new UserAccount(UUID.randomUUID(), username, passwords.encode("inspection-test-password"), role, employee));
        var login = http.postForEntity("/auth/login", Map.of("username", username, "password", "inspection-test-password"), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(login.getBody().path("token").asText()); return headers;
    }
}
