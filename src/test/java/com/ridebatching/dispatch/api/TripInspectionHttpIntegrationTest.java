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
        }
        JsonNode schema = http.getForObject("/v3/api-docs", JsonNode.class).path("components").path("schemas");
        assertThat(schema.path("DispatchView").path("properties").has("planningCapacity")).isTrue();
        assertThat(schema.path("DispatchView").path("properties").has("bookingIds")).isTrue();
        assertThat(schema.path("Stop").path("properties").has("pickupSlaDeadline")).isTrue();
        assertThat(schema.path("Stop").path("properties").has("withinPickupSla")).isTrue();
        assertThat(response("/api/admin/dispatch/trips/" + UUID.randomUUID(), admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
