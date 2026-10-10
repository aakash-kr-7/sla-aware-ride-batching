package com.ridebatching.batching.constraint;

import com.ridebatching.batching.FeasibilityResult;
import com.ridebatching.batching.constraint.DetourConstraint.DetourRejectionDetails;
import com.ridebatching.booking.Booking;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.geo.TravelTimeEstimator;
import com.ridebatching.trip.TripStop;
import com.ridebatching.trip.TripStopType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DetourConstraintTest {

    private DetourConstraint constraint;

    @BeforeEach
    void setUp() {
        // 1D estimator: 1 minute of travel per unit of longitude distance
        TravelTimeEstimator estimator = (origin, destination) -> {
            double dist = Math.abs(origin.longitude() - destination.longitude());
            return Duration.ofMillis((long) (dist * 60_000));
        };
        // 40% relative, 15 minutes absolute
        constraint = new DetourConstraint(estimator, 0.40, Duration.ofMinutes(15));
    }

    private Booking mockBooking(UUID id, double pickupLon, double dropoffLon) {
        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(id);
        when(booking.getPickupLocation()).thenReturn(new GeoPoint(0, pickupLon));
        when(booking.getDropoffLocation()).thenReturn(new GeoPoint(0, dropoffLon));
        return booking;
    }

    private TripStop mockStop(UUID bookingId, TripStopType type, double lon) {
        return new TripStop(UUID.randomUUID(), bookingId, type, new GeoPoint(0, lon));
    }

    @Test
    void shouldAcceptWithNoDetour() {
        UUID b1 = UUID.randomUUID();
        Booking booking1 = mockBooking(b1, 0, 10);

        List<TripStop> route = List.of(
                mockStop(b1, TripStopType.PICKUP, 0),
                mockStop(b1, TripStopType.DROPOFF, 10)
        );

        FeasibilityResult result = constraint.evaluate(route, Map.of(b1, booking1));

        assertThat(result.isFeasible()).isTrue();
    }

    @Test
    void shouldAcceptExactThresholdDetour() {
        UUID b1 = UUID.randomUUID();
        UUID b2 = UUID.randomUUID();

        // B1 direct: 10 mins. Allowed relative detour = 4 mins.
        Booking booking1 = mockBooking(b1, 0, 10);
        Booking booking2 = mockBooking(b2, 2, 0);

        // Route: B1_P(0) -> B2_P(2) -> B2_D(0) -> B1_D(10)
        // B1 travel time: 2 + 2 + 10 = 14 mins.
        // Detour = 4 mins. Exactly on threshold (0.4 * 10).
        List<TripStop> route = List.of(
                mockStop(b1, TripStopType.PICKUP, 0),
                mockStop(b2, TripStopType.PICKUP, 2),
                mockStop(b2, TripStopType.DROPOFF, 0),
                mockStop(b1, TripStopType.DROPOFF, 10)
        );

        FeasibilityResult result = constraint.evaluate(route, Map.of(b1, booking1, b2, booking2));

        assertThat(result.isFeasible()).isTrue();
    }

    @Test
    void shouldRejectWhenRelativeThresholdExceeded() {
        UUID b1 = UUID.randomUUID();
        UUID b2 = UUID.randomUUID();

        // B1 direct: 10 mins. Allowed relative detour = 4 mins.
        Booking booking1 = mockBooking(b1, 0, 10);
        Booking booking2 = mockBooking(b2, 2.5, 0);

        // Route: B1_P(0) -> B2_P(2.5) -> B2_D(0) -> B1_D(10)
        // B1 travel time: 2.5 + 2.5 + 10 = 15 mins.
        // Detour = 5 mins. Exceeds 4 mins limit.
        List<TripStop> route = List.of(
                mockStop(b1, TripStopType.PICKUP, 0),
                mockStop(b2, TripStopType.PICKUP, 2.5),
                mockStop(b2, TripStopType.DROPOFF, 0),
                mockStop(b1, TripStopType.DROPOFF, 10)
        );

        FeasibilityResult result = constraint.evaluate(route, Map.of(b1, booking1, b2, booking2));

        assertThat(result.isFeasible()).isFalse();
        DetourRejectionDetails details = (DetourRejectionDetails) result.rejectionDetails();
        assertThat(details.constraintCode()).isEqualTo("RELATIVE_DETOUR");
        assertThat(details.affectedBooking()).isEqualTo(b1);
        assertThat(details.directTravelTime()).isEqualTo(Duration.ofMinutes(10));
        assertThat(details.sharedTravelTime()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void shouldRejectWhenAbsoluteThresholdExceeded() {
        UUID b1 = UUID.randomUUID();
        UUID b2 = UUID.randomUUID();

        // B1 direct: 50 mins.
        // Max relative detour = 20 mins. Max absolute detour = 15 mins.
        // Absolute constraint is stricter here.
        Booking booking1 = mockBooking(b1, 0, 50);
        Booking booking2 = mockBooking(b2, 8, 0);

        // Route: B1_P(0) -> B2_P(8) -> B2_D(0) -> B1_D(50)
        // B1 travel time: 8 + 8 + 50 = 66 mins.
        // Detour = 16 mins. Exceeds absolute limit of 15.
        List<TripStop> route = List.of(
                mockStop(b1, TripStopType.PICKUP, 0),
                mockStop(b2, TripStopType.PICKUP, 8),
                mockStop(b2, TripStopType.DROPOFF, 0),
                mockStop(b1, TripStopType.DROPOFF, 50)
        );

        FeasibilityResult result = constraint.evaluate(route, Map.of(b1, booking1, b2, booking2));

        assertThat(result.isFeasible()).isFalse();
        DetourRejectionDetails details = (DetourRejectionDetails) result.rejectionDetails();
        assertThat(details.constraintCode()).isEqualTo("ABSOLUTE_DETOUR");
        assertThat(details.affectedBooking()).isEqualTo(b1);
        assertThat(details.directTravelTime()).isEqualTo(Duration.ofMinutes(50));
        assertThat(details.sharedTravelTime()).isEqualTo(Duration.ofMinutes(66));
    }

    @Test
    void shouldIdentifyExistingPassengerHarmedByInsertion() {
        UUID existingBooking = UUID.randomUUID();
        UUID newBooking = UUID.randomUUID();

        // Existing direct: 10 mins. Allowed relative detour = 4 mins.
        Booking existing = mockBooking(existingBooking, 0, 10);
        Booking newRider = mockBooking(newBooking, 2.5, 0);

        // New rider causes a 5 minute detour for the existing rider.
        // Existing rider travel: 0 -> 2.5 -> 0 -> 10 = 15 mins. Detour = 5 mins.
        // New rider travel: 2.5 -> 0 = 2.5 mins. Direct = 2.5 mins. Detour = 0.
        List<TripStop> route = List.of(
                mockStop(existingBooking, TripStopType.PICKUP, 0),
                mockStop(newBooking, TripStopType.PICKUP, 2.5),
                mockStop(newBooking, TripStopType.DROPOFF, 0),
                mockStop(existingBooking, TripStopType.DROPOFF, 10)
        );

        FeasibilityResult result = constraint.evaluate(route, Map.of(existingBooking, existing, newBooking, newRider));

        assertThat(result.isFeasible()).isFalse();
        DetourRejectionDetails details = (DetourRejectionDetails) result.rejectionDetails();
        assertThat(details.constraintCode()).isEqualTo("RELATIVE_DETOUR");
        
        // The one failing the constraint is the existing rider, not the new insertion
        assertThat(details.affectedBooking()).isEqualTo(existingBooking);
    }
}
