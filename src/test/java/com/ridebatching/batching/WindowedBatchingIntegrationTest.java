package com.ridebatching.batching;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.booking.*;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.TripRepository;
import com.ridebatching.zone.ServiceableZone;
import com.ridebatching.zone.ServiceableZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "app.batching.max-bookings-per-cycle=6")
class WindowedBatchingIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T10:17:00Z");
    private static final GeoPoint FIRST_ZONE = new GeoPoint(0, 0);
    private static final GeoPoint SECOND_ZONE = new GeoPoint(0.008, 0);
    private static final GeoPoint NORTH = new GeoPoint(0.02, 0);
    @Autowired private BatchingService batching;
    @Autowired private BatchClaimPolicy policy;
    @Autowired private BookingRepository bookings;
    @Autowired private EmployeeRepository employees;
    @Autowired private TripRepository trips;
    @Autowired private ServiceableZoneRepository zones;
    @Autowired private JdbcTemplate jdbc;
    @MockBean(name = "batchingClock") private Clock clock;
    private Employee employee;
    private long sequence;

    @BeforeEach
    void seedReferenceData() {
        when(clock.instant()).thenReturn(NOW);
        employee = employees.saveAndFlush(new Employee(UUID.randomUUID(), "WINDOW", "Window test", "window@example.invalid"));
        zones.saveAllAndFlush(List.of(new ServiceableZone("First", 0, 0, 600, true),
                new ServiceableZone("Second", 0.008, 0, 600, true)));
    }

    @Test
    void partitionsRealTripsBySourceWindowZoneAndDirection() {
        Booking earlierA = seed("2026-10-11T09:55:00Z", FIRST_ZONE, NORTH, 1200);
        Booking earlierB = seed("2026-10-11T09:56:00Z", FIRST_ZONE, NORTH, 1200);
        Booking laterA = seed("2026-10-11T10:03:00Z", FIRST_ZONE, NORTH, 1200);
        Booking laterB = seed("2026-10-11T10:04:00Z", FIRST_ZONE, NORTH, 1200);
        Booking opposite = seed("2026-10-11T10:05:00Z", FIRST_ZONE, new GeoPoint(-0.02, 0), 1200);
        Booking otherZone = seed("2026-10-11T10:06:00Z", SECOND_ZONE, NORTH, 1200);
        Booking openWindow = seed("2026-10-11T10:15:00Z", FIRST_ZONE, NORTH, 301);
        Booking expired = seed("2026-10-11T09:55:00Z", FIRST_ZONE, NORTH, -1);

        assertThat(batching.formBatches()).isEqualTo(new BatchingService.BatchingResult(6, 4, 0));
        UUID earlierTrip = confirmedTrip(earlierA);
        UUID laterTrip = confirmedTrip(laterA);
        assertThat(confirmedTrip(earlierB)).isEqualTo(earlierTrip);
        assertThat(confirmedTrip(laterB)).isEqualTo(laterTrip);
        assertThat(List.of(earlierTrip, laterTrip, confirmedTrip(opposite), confirmedTrip(otherZone)))
                .doesNotHaveDuplicates();
        assertPending(openWindow);
        assertPending(expired);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_stops", Integer.class)).isEqualTo(12);
    }

    @Test
    void processesUrgentOpenWindowRequestsBeforeCollectionClosesAndStillSplitsForSla() {
        Booking first = seed("2026-10-11T10:16:00Z", FIRST_ZONE, NORTH, 90);
        Booking second = seed("2026-10-11T10:16:01Z", FIRST_ZONE, NORTH, 90);
        Booking wait = seed("2026-10-11T10:16:02Z", FIRST_ZONE, NORTH, 301);

        assertThat(batching.formBatches()).isEqualTo(new BatchingService.BatchingResult(2, 2, 0));
        assertThat(confirmedTrip(first)).isNotEqualTo(confirmedTrip(second));
        assertPending(wait);
    }

    @Test
    void includesTheExactUrgencyBoundaryAndKeepsOpenSourceWindowsSeparate() {
        Booking closed = seed("2026-10-11T10:14:59Z", FIRST_ZONE, NORTH, 1200);
        Booking urgent = seed("2026-10-11T10:15:00Z", FIRST_ZONE, NORTH, 300);
        assertThat(batching.formBatches()).isEqualTo(new BatchingService.BatchingResult(2, 2, 0));
        assertThat(confirmedTrip(closed)).isNotEqualTo(confirmedTrip(urgent));
    }

    @Test
    void boundsEachServiceCycleAndLeavesTheOverflowForTheNextCycle() {
        List<Booking> candidates = java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> seed("2026-10-11T10:03:00Z", FIRST_ZONE, NORTH, 1200 + i)).toList();
        assertThat(batching.formBatches().bookingsConsidered()).isEqualTo(6);
        candidates.subList(0, 6).forEach(this::confirmedTrip);
        assertPending(candidates.get(6));
        assertThat(batching.formBatches()).isEqualTo(new BatchingService.BatchingResult(1, 1, 0));
        confirmedTrip(candidates.get(6));
    }

    @Test
    void preservesTheFourPlusOneCapacitySplitInsideACluster() {
        List<Booking> candidates = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> seed("2026-10-11T10:03:00Z", FIRST_ZONE, NORTH, 1200)).toList();
        assertThat(batching.formBatches()).isEqualTo(new BatchingService.BatchingResult(5, 2, 0));
        assertThat(candidates.stream().map(this::confirmedTrip).toList()).hasSize(5);
        assertThat(jdbc.queryForList("""
                SELECT count(*) FROM trip_stops WHERE type='PICKUP' GROUP BY trip_id ORDER BY count(*)
                """, Long.class)).containsExactly(1L, 4L);
    }

    @Test
    void doesNotPlanRequestsWhosePickupNoLongerHasAnActiveZone() {
        Booking removedZone = seed("2026-10-11T10:03:00Z", FIRST_ZONE, NORTH, 1200);
        zones.deleteAll();
        assertThat(batching.formBatches()).isEqualTo(new BatchingService.BatchingResult(1, 0, 1));
        assertPending(removedZone);
        assertThat(trips.count()).isZero();
    }

    @Test
    void aRequestExactlyAtItsDeadlineCanStillFormASoloTrip() {
        Booking boundary = seed("2026-10-11T10:16:00Z", FIRST_ZONE, NORTH, 0);
        assertThat(batching.formBatches()).isEqualTo(new BatchingService.BatchingResult(1, 1, 0));
        confirmedTrip(boundary);
    }

    @Test
    void futureDatedAndExpiredRequestsDoNotCrowdOutAnEligibleRequest() {
        for (int i = 0; i < 7; i++) {
            seed("2026-10-11T10:03:00Z", FIRST_ZONE, NORTH, -1);
            seed("2026-10-11T10:18:00Z", FIRST_ZONE, NORTH, 100);
        }
        Booking eligible = seed("2026-10-11T10:03:00Z", FIRST_ZONE, NORTH, 1200);
        assertThat(batching.formBatches()).isEqualTo(new BatchingService.BatchingResult(1, 1, 0));
        confirmedTrip(eligible);
        assertThat(batching.formBatches()).isEqualTo(new BatchingService.BatchingResult(0, 0, 0));
    }

    private Booking seed(String createdAt, GeoPoint pickup, GeoPoint dropoff, long remainingSeconds) {
        Instant created = Instant.parse(createdAt);
        UUID id = new UUID(0, ++sequence);
        return bookings.saveAndFlush(new Booking(id, employee, pickup, dropoff, policy.windowFor(created).start(),
                BookingStatus.PENDING, NOW.plusSeconds(remainingSeconds), created, id.toString()));
    }

    private UUID confirmedTrip(Booking booking) {
        Booking saved = bookings.findById(booking.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(saved.getTripId()).isNotNull();
        return saved.getTripId();
    }

    private void assertPending(Booking booking) {
        Booking saved = bookings.findById(booking.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(saved.getTripId()).isNull();
    }
}
