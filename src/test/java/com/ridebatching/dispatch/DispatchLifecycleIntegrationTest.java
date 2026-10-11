package com.ridebatching.dispatch;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.driver.*;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@org.springframework.boot.test.context.SpringBootTest(properties = "dispatch.expiry-scheduling.max-trips-per-cycle=1")
class DispatchLifecycleIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");
    @Autowired DispatchService dispatch;
    @Autowired DriverRepository drivers;
    @Autowired DriverOfferRepository offers;
    @Autowired TripRepository trips;
    @Autowired DispatchExpiryJob expiry;
    @MockBean(name = "dispatchClock") Clock clock;
    private UUID tripId;

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        tripId = trips.saveAndFlush(new Trip(UUID.randomUUID(), NOW, NOW)).getId();
    }

    @Test void repeatedAdvanceKeepsOneOfferAndRejectionAutomaticallyOffersTheNextDriver() {
        Driver nearest = driver(1), backup = driver(2);
        DispatchView first = dispatch.advanceTrip(tripId);
        assertThat(first.offers().get(0).driverId()).isEqualTo(nearest.getId());
        assertThat(dispatch.advanceTrip(tripId).activeOfferId()).isEqualTo(first.activeOfferId());
        DispatchView next = dispatch.rejectOffer(first.activeOfferId());
        assertThat(next.attemptsUsed()).isEqualTo(2);
        assertThat(offers.findById(next.activeOfferId()).orElseThrow().getDriverId()).isEqualTo(backup.getId());
        assertThat(dispatch.rejectOffer(first.activeOfferId()).activeOfferId()).isEqualTo(next.activeOfferId());
        dispatch.acceptOffer(next.activeOfferId());
        dispatch.acceptOffer(next.activeOfferId());
        assertThat(dispatch.getTrip(tripId).outcome()).isEqualTo(DispatchOutcome.ASSIGNED);
        assertThat(drivers.findById(backup.getId()).orElseThrow().getActiveTripId()).isEqualTo(tripId);
    }

    @Test void allDriversRejectAndElapsedCoolOffCannotRepeatAnAttempt() {
        driver(1); driver(2);
        DispatchView first = dispatch.advanceTrip(tripId);
        DispatchView next = dispatch.rejectOffer(first.activeOfferId());
        DispatchView exhausted = dispatch.rejectOffer(next.activeOfferId());
        assertThat(exhausted.outcome()).isEqualTo(DispatchOutcome.DRIVERS_EXHAUSTED);
        when(clock.instant()).thenReturn(NOW.plusSeconds(600));
        assertThat(dispatch.advanceTrip(tripId).activeOfferId()).isNull();
        assertThat(offers.findByTripId(tripId)).hasSize(2).extracting(DriverOffer::getDriverId).doesNotHaveDuplicates();
    }

    @Test void noDriverLeavesTripPlannedAndANewlyAvailableDriverCanBeConsidered() {
        assertThat(dispatch.advanceTrip(tripId).outcome()).isEqualTo(DispatchOutcome.NO_ELIGIBLE_DRIVER);
        assertThat(offers.count()).isZero();
        driver(1);
        assertThat(dispatch.advanceTrip(tripId).outcome()).isEqualTo(DispatchOutcome.OFFERED);
    }

    @Test void offerIsInvalidAtExactDeadlineAndExpiryCommitsWithNextOfferDespiteConflict() {
        driver(1); driver(2);
        UUID first = dispatch.advanceTrip(tripId).activeOfferId();
        when(clock.instant()).thenReturn(NOW.plusSeconds(119));
        assertThat(dispatch.getTrip(tripId).outcome()).isEqualTo(DispatchOutcome.OFFERED);
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        assertThat(dispatch.getTrip(tripId).outcome()).isEqualTo(DispatchOutcome.OFFER_EXPIRED);
        assertThatThrownBy(() -> dispatch.acceptOffer(first)).isInstanceOf(OfferExpiredException.class);
        assertThat(offers.findById(first).orElseThrow().getStatus()).isEqualTo(DriverOfferStatus.EXPIRED);
        DispatchView next = dispatch.getTrip(tripId);
        assertThat(next.activeOfferId()).isNotEqualTo(first).isNotNull();
        assertThat(next.attemptsUsed()).isEqualTo(2);
        assertThatThrownBy(() -> dispatch.acceptOffer(first)).isInstanceOf(OfferExpiredException.class);
        assertThat(offers.count()).isEqualTo(2);
    }

    @Test void offerLimitStopsEvenWhenMoreDriversAreAvailable() {
        driver(1); driver(2); driver(3); driver(4);
        DispatchView state = dispatch.advanceTrip(tripId);
        for (int i = 0; i < 3; i++) state = dispatch.rejectOffer(state.activeOfferId());
        assertThat(state.outcome()).isEqualTo(DispatchOutcome.ATTEMPT_LIMIT_REACHED);
        assertThat(dispatch.advanceTrip(tripId).attemptsUsed()).isEqualTo(3);
        assertThat(offers.findByTripId(tripId)).extracting(DriverOffer::getDriverId).doesNotHaveDuplicates();
    }

    @Test void expiryJobBoundsItsCycleAndEventuallyAdvancesEveryDueTrip() {
        driver(1); driver(2);
        UUID otherTrip = trips.saveAndFlush(new Trip(UUID.randomUUID(), NOW, NOW)).getId();
        dispatch.advanceTrip(tripId); dispatch.advanceTrip(otherTrip);
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        assertThat(expiry.advanceExpiredOffers()).isEqualTo(1);
        assertThat(expiry.advanceExpiredOffers()).isEqualTo(1);
        assertThat(expiry.advanceExpiredOffers()).isZero();
        assertThat(offers.findAll()).filteredOn(o -> o.getStatus() == DriverOfferStatus.EXPIRED).hasSize(2);
        assertThat(dispatch.getTrip(tripId).attemptsUsed()).isEqualTo(2);
        assertThat(dispatch.getTrip(otherTrip).attemptsUsed()).isEqualTo(2);
    }

    private Driver driver(double latitude) {
        Driver d = new Driver(UUID.randomUUID(), "Driver", 4);
        d.updateLocation(new GeoPoint(latitude, 0)); d.goOnline();
        return drivers.saveAndFlush(d);
    }
}
