package com.ridebatching.trip;

import com.ridebatching.geo.GeoPoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripTest {

    private final GeoPoint point = new GeoPoint(0, 0);

    @Test
    void shouldAcceptValidRouteWithOneBooking() {
        Trip trip = new Trip(UUID.randomUUID(), Instant.now(), Instant.now());
        UUID bookingId = UUID.randomUUID();

        TripStop pickup = new TripStop(UUID.randomUUID(), bookingId, TripStopType.PICKUP, point);
        TripStop dropoff = new TripStop(UUID.randomUUID(), bookingId, TripStopType.DROPOFF, point);

        trip.assignRoute(List.of(pickup, dropoff));

        assertThat(trip.getStops()).hasSize(2);
        assertThat(trip.getStops().get(0).getSequenceOrder()).isEqualTo(1);
        assertThat(trip.getStops().get(1).getSequenceOrder()).isEqualTo(2);
    }

    @Test
    void shouldAcceptValidRouteWithMultipleBookings() {
        Trip trip = new Trip(UUID.randomUUID(), Instant.now(), Instant.now());
        UUID b1 = UUID.randomUUID();
        UUID b2 = UUID.randomUUID();

        TripStop p1 = new TripStop(UUID.randomUUID(), b1, TripStopType.PICKUP, point);
        TripStop p2 = new TripStop(UUID.randomUUID(), b2, TripStopType.PICKUP, point);
        TripStop d1 = new TripStop(UUID.randomUUID(), b1, TripStopType.DROPOFF, point);
        TripStop d2 = new TripStop(UUID.randomUUID(), b2, TripStopType.DROPOFF, point);

        // Sequence: P1 -> P2 -> D1 -> D2
        trip.assignRoute(List.of(p1, p2, d1, d2));

        assertThat(trip.getStops()).hasSize(4);
        assertThat(trip.getStops().get(0).getBookingId()).isEqualTo(b1);
        assertThat(trip.getStops().get(3).getBookingId()).isEqualTo(b2);
    }

    @Test
    void shouldRejectRouteWhereDropoffOccursBeforePickup() {
        Trip trip = new Trip(UUID.randomUUID(), Instant.now(), Instant.now());
        UUID bookingId = UUID.randomUUID();

        TripStop pickup = new TripStop(UUID.randomUUID(), bookingId, TripStopType.PICKUP, point);
        TripStop dropoff = new TripStop(UUID.randomUUID(), bookingId, TripStopType.DROPOFF, point);

        assertThatThrownBy(() -> trip.assignRoute(List.of(dropoff, pickup)))
                .isInstanceOf(InvalidRouteException.class)
                .hasMessageContaining("Dropoff occurs before pickup for booking: " + bookingId);
    }

    @Test
    void shouldRejectRouteWithMissingDropoff() {
        Trip trip = new Trip(UUID.randomUUID(), Instant.now(), Instant.now());
        UUID bookingId = UUID.randomUUID();

        TripStop pickup = new TripStop(UUID.randomUUID(), bookingId, TripStopType.PICKUP, point);

        assertThatThrownBy(() -> trip.assignRoute(List.of(pickup)))
                .isInstanceOf(InvalidRouteException.class)
                .hasMessageContaining("Missing dropoff for booking: " + bookingId);
    }

    @Test
    void shouldRejectRouteWithDuplicatePickup() {
        Trip trip = new Trip(UUID.randomUUID(), Instant.now(), Instant.now());
        UUID bookingId = UUID.randomUUID();

        TripStop pickup1 = new TripStop(UUID.randomUUID(), bookingId, TripStopType.PICKUP, point);
        TripStop pickup2 = new TripStop(UUID.randomUUID(), bookingId, TripStopType.PICKUP, point);
        TripStop dropoff = new TripStop(UUID.randomUUID(), bookingId, TripStopType.DROPOFF, point);

        assertThatThrownBy(() -> trip.assignRoute(List.of(pickup1, pickup2, dropoff)))
                .isInstanceOf(InvalidRouteException.class)
                .hasMessageContaining("Duplicate pickup for booking: " + bookingId);
    }

    @Test
    void shouldRejectRouteWithDuplicateDropoff() {
        Trip trip = new Trip(UUID.randomUUID(), Instant.now(), Instant.now());
        UUID bookingId = UUID.randomUUID();

        TripStop pickup = new TripStop(UUID.randomUUID(), bookingId, TripStopType.PICKUP, point);
        TripStop dropoff1 = new TripStop(UUID.randomUUID(), bookingId, TripStopType.DROPOFF, point);
        TripStop dropoff2 = new TripStop(UUID.randomUUID(), bookingId, TripStopType.DROPOFF, point);

        assertThatThrownBy(() -> trip.assignRoute(List.of(pickup, dropoff1, dropoff2)))
                .isInstanceOf(InvalidRouteException.class)
                .hasMessageContaining("Duplicate dropoff for booking: " + bookingId);
    }
}
