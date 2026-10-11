package com.ridebatching.booking;

import com.ridebatching.geo.GeoPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class BookingTransitionTest {
    @Test void confirmationNeedsATripAndTheNormalProgressionCannotSkipAState() {
        Booking b = booking(BookingStatus.PENDING);
        assertThatThrownBy(b::confirm).isInstanceOf(IllegalStateException.class);
        b.setTripId(UUID.randomUUID()); b.confirm();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThatThrownBy(b::dropOff).isInstanceOf(IllegalStateException.class);
        b.pickUp(); b.dropOff();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.DROPPED);
        assertThatThrownBy(b::cancel).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @EnumSource(value = BookingStatus.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
    void pickupRejectsEveryOtherState(BookingStatus state) {
        assertThatThrownBy(booking(state)::pickUp).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @EnumSource(value = BookingStatus.class, names = "PICKED_UP", mode = EnumSource.Mode.EXCLUDE)
    void dropoffRejectsEveryOtherState(BookingStatus state) {
        assertThatThrownBy(booking(state)::dropOff).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @EnumSource(value = BookingStatus.class, names = {"PENDING", "CONFIRMED", "CANCELLED"})
    void cancellationIsAllowedOnlyBeforePickupAndItsRetryIsIdempotent(BookingStatus state) {
        Booking b = booking(state); b.cancel(); b.cancel();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }
    private Booking booking(BookingStatus state) {
        return new Booking(UUID.randomUUID(), null, new GeoPoint(0, 0), new GeoPoint(0.01, 0),
                Instant.EPOCH, state, Instant.EPOCH.plusSeconds(1200), Instant.EPOCH, "transition");
    }
}
