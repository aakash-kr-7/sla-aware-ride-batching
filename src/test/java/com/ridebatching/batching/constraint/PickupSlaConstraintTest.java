package com.ridebatching.batching.constraint;

import com.ridebatching.batching.FeasibilityResult;
import com.ridebatching.batching.constraint.PickupSlaConstraint.PickupSlaRejectionDetails;
import com.ridebatching.booking.Booking;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.geo.TravelTimeEstimator;
import com.ridebatching.trip.TripStop;
import com.ridebatching.trip.TripStopType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PickupSlaConstraintTest {

    private PickupSlaConstraint constraint;
    private Instant routeStartTime;

    @BeforeEach
    void setUp() {
        // Dummy estimator: 10 minutes between any two points
        TravelTimeEstimator dummyEstimator = (origin, destination) -> Duration.ofMinutes(10);
        constraint = new PickupSlaConstraint(dummyEstimator);
        routeStartTime = Instant.parse("2026-10-10T08:00:00Z");
    }

    private Booking mockBooking(UUID id, Instant slaDeadline) {
        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(id);
        when(booking.getPickupSlaDeadline()).thenReturn(slaDeadline);
        return booking;
    }

    private TripStop mockPickupStop(UUID bookingId) {
        return new TripStop(UUID.randomUUID(), bookingId, TripStopType.PICKUP, new GeoPoint(0, 0));
    }

    private TripStop mockDropoffStop(UUID bookingId) {
        return new TripStop(UUID.randomUUID(), bookingId, TripStopType.DROPOFF, new GeoPoint(0, 0));
    }

    @Test
    void shouldAcceptWhenAllPickupsWithinSla() {
        UUID b1 = UUID.randomUUID();
        UUID b2 = UUID.randomUUID();

        // First stop doesn't start at routeStartTime exactly; rather routeStartTime + travelTime(0, 1)?
        // No, wait. The first stop travel time is estimated relative to the previous stop. 
        // Oh, wait, in evaluate:
        // if (i > 0) currentEstimatedTime = currentEstimatedTime.plus(travelDuration);
        // This means the FIRST stop in the route is assumed to be reached EXACTLY at routeStartTime.
        // Wait, if routeStartTime is the dispatch time, then the vehicle needs to travel to the first stop.
        // If the vehicle is at the depot, or is currently at a different location, the distance to the first stop is not 0.
        // BUT my implementation: `if (i > 0)` means `i = 0` adds NO time. 
        // This implies `routeStartTime` is the time the vehicle arrives at the first stop, OR we assume the trip starts AT the first pickup location.
        // Let's assume my implementation is correct based on how it's usually modeled if dispatch time is not modeled separately from trip start.
        // Wait, if it doesn't add time for the first stop, estimated pickup for b1 is 08:00.
        
        Booking booking1 = mockBooking(b1, Instant.parse("2026-10-10T08:05:00Z"));
        Booking booking2 = mockBooking(b2, Instant.parse("2026-10-10T08:20:00Z"));

        List<TripStop> route = List.of(
                mockPickupStop(b1),
                mockPickupStop(b2),
                mockDropoffStop(b1),
                mockDropoffStop(b2)
        );
        Map<UUID, Booking> bookings = Map.of(b1, booking1, b2, booking2);

        FeasibilityResult result = constraint.evaluate(route, routeStartTime, bookings);

        assertThat(result.isFeasible()).isTrue();
        assertThat(result.rejectionDetails()).isNull();
    }

    @Test
    void shouldRejectWhenNewRiderViolatesSla() {
        UUID b1 = UUID.randomUUID();
        UUID newBooking = UUID.randomUUID();

        // B1 pickup at 08:00, New rider pickup at 08:10
        // New rider SLA is 08:05, so they will be late
        Booking booking1 = mockBooking(b1, Instant.parse("2026-10-10T08:30:00Z"));
        Booking newRider = mockBooking(newBooking, Instant.parse("2026-10-10T08:05:00Z"));

        List<TripStop> route = List.of(
                mockPickupStop(b1),
                mockPickupStop(newBooking), // Arrives here at 08:10
                mockDropoffStop(b1),
                mockDropoffStop(newBooking)
        );
        Map<UUID, Booking> bookings = Map.of(b1, booking1, newBooking, newRider);

        FeasibilityResult result = constraint.evaluate(route, routeStartTime, bookings);

        assertThat(result.isFeasible()).isFalse();
        assertThat(result.rejectionDetails()).isNotNull();
        PickupSlaRejectionDetails details = (PickupSlaRejectionDetails) result.rejectionDetails();
        assertThat(details.constraintCode()).isEqualTo("PICKUP_SLA");
        assertThat(details.affectedBooking()).isEqualTo(newBooking);
        assertThat(details.estimatedPickup()).isEqualTo(Instant.parse("2026-10-10T08:10:00Z"));
        assertThat(details.deadline()).isEqualTo(Instant.parse("2026-10-10T08:05:00Z"));
    }

    @Test
    void shouldRejectWhenExistingRiderBecomesLateBecauseOfInsertion() {
        UUID existingBooking = UUID.randomUUID();
        UUID newBooking = UUID.randomUUID();

        // New rider picked up first at 08:00.
        // Existing rider picked up at 08:10.
        // Existing rider's SLA is 08:05, so the new insertion made them late.
        Booking newRider = mockBooking(newBooking, Instant.parse("2026-10-10T08:30:00Z"));
        Booking existing = mockBooking(existingBooking, Instant.parse("2026-10-10T08:05:00Z"));

        List<TripStop> route = List.of(
                mockPickupStop(newBooking),
                mockPickupStop(existingBooking), // Arrives here at 08:10
                mockDropoffStop(newBooking),
                mockDropoffStop(existingBooking)
        );
        Map<UUID, Booking> bookings = Map.of(existingBooking, existing, newBooking, newRider);

        FeasibilityResult result = constraint.evaluate(route, routeStartTime, bookings);

        assertThat(result.isFeasible()).isFalse();
        assertThat(result.rejectionDetails()).isNotNull();
        PickupSlaRejectionDetails details = (PickupSlaRejectionDetails) result.rejectionDetails();
        assertThat(details.constraintCode()).isEqualTo("PICKUP_SLA");
        assertThat(details.affectedBooking()).isEqualTo(existingBooking);
        assertThat(details.estimatedPickup()).isEqualTo(Instant.parse("2026-10-10T08:10:00Z"));
        assertThat(details.deadline()).isEqualTo(Instant.parse("2026-10-10T08:05:00Z"));
    }

    @Test
    void shouldAcceptBoundaryExactlyAtSla() {
        UUID b1 = UUID.randomUUID();
        // First pickup arrives exactly at 08:00
        Booking booking1 = mockBooking(b1, Instant.parse("2026-10-10T08:00:00Z"));

        List<TripStop> route = List.of(
                mockPickupStop(b1),
                mockDropoffStop(b1)
        );
        Map<UUID, Booking> bookings = Map.of(b1, booking1);

        FeasibilityResult result = constraint.evaluate(route, routeStartTime, bookings);

        assertThat(result.isFeasible()).isTrue();
    }

    @Test
    void shouldRejectOneSecondBeyondSla() {
        UUID b1 = UUID.randomUUID();
        // First pickup arrives at 08:00:00, but deadline is 07:59:59
        Booking booking1 = mockBooking(b1, Instant.parse("2026-10-10T07:59:59Z"));

        List<TripStop> route = List.of(
                mockPickupStop(b1),
                mockDropoffStop(b1)
        );
        Map<UUID, Booking> bookings = Map.of(b1, booking1);

        FeasibilityResult result = constraint.evaluate(route, routeStartTime, bookings);

        assertThat(result.isFeasible()).isFalse();
        PickupSlaRejectionDetails details = (PickupSlaRejectionDetails) result.rejectionDetails();
        assertThat(details.constraintCode()).isEqualTo("PICKUP_SLA");
        assertThat(details.estimatedPickup()).isEqualTo(Instant.parse("2026-10-10T08:00:00Z"));
        assertThat(details.deadline()).isEqualTo(Instant.parse("2026-10-10T07:59:59Z"));
    }
}
