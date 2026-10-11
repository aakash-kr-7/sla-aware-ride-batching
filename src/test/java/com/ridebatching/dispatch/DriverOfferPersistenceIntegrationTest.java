package com.ridebatching.dispatch;

import com.ridebatching.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriverOfferPersistenceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DriverOfferRepository offerRepository;
    @Autowired private com.ridebatching.driver.DriverRepository drivers;
    @Autowired private com.ridebatching.trip.TripRepository trips;

    @Test
    void shouldPersistAndRetrieveOffer() {
        DriverOffer offer = new DriverOffer(UUID.randomUUID(), driverId(), tripId());
        offerRepository.saveAndFlush(offer);

        DriverOffer retrieved = offerRepository.findById(offer.getId()).orElseThrow();
        assertThat(retrieved.getStatus()).isEqualTo(DriverOfferStatus.OFFERED);
    }

    @Test
    void shouldPreventMultipleAcceptedOffersForSameTrip() {
        UUID tripId = tripId();
        
        DriverOffer offer1 = new DriverOffer(UUID.randomUUID(), driverId(), tripId);
        offer1.accept();
        offerRepository.saveAndFlush(offer1);

        DriverOffer offer2 = new DriverOffer(UUID.randomUUID(), driverId(), tripId);
        offer2.accept();
        
        assertThatThrownBy(() -> offerRepository.saveAndFlush(offer2))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("idx_accepted_trip_offer");
    }

    @Test
    void shouldAllowMultipleRejectedOffersForSameTrip() {
        UUID tripId = tripId();
        
        DriverOffer offer1 = new DriverOffer(UUID.randomUUID(), driverId(), tripId);
        offer1.reject();
        offerRepository.saveAndFlush(offer1);

        DriverOffer offer2 = new DriverOffer(UUID.randomUUID(), driverId(), tripId);
        offer2.reject();
        offerRepository.saveAndFlush(offer2);

        assertThat(offerRepository.findByTripId(tripId)).hasSize(2);
    }

    private UUID driverId() {
        return drivers.saveAndFlush(new com.ridebatching.driver.Driver(UUID.randomUUID(), "test", 4)).getId();
    }
    private UUID tripId() {
        var now = java.time.Instant.now();
        return trips.saveAndFlush(new com.ridebatching.trip.Trip(UUID.randomUUID(), now, now)).getId();
    }
}
