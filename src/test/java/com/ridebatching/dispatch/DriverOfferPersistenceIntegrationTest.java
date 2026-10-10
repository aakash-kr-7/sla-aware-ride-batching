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

    @Test
    void shouldPersistAndRetrieveOffer() {
        DriverOffer offer = new DriverOffer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        offerRepository.saveAndFlush(offer);

        DriverOffer retrieved = offerRepository.findById(offer.getId()).orElseThrow();
        assertThat(retrieved.getStatus()).isEqualTo(DriverOfferStatus.OFFERED);
    }

    @Test
    void shouldPreventMultipleAcceptedOffersForSameTrip() {
        UUID tripId = UUID.randomUUID();
        
        DriverOffer offer1 = new DriverOffer(UUID.randomUUID(), UUID.randomUUID(), tripId);
        offer1.accept();
        offerRepository.saveAndFlush(offer1);

        DriverOffer offer2 = new DriverOffer(UUID.randomUUID(), UUID.randomUUID(), tripId);
        offer2.accept();
        
        assertThatThrownBy(() -> offerRepository.saveAndFlush(offer2))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("idx_accepted_trip_offer");
    }

    @Test
    void shouldAllowMultipleRejectedOffersForSameTrip() {
        UUID tripId = UUID.randomUUID();
        
        DriverOffer offer1 = new DriverOffer(UUID.randomUUID(), UUID.randomUUID(), tripId);
        offer1.reject();
        offerRepository.saveAndFlush(offer1);

        DriverOffer offer2 = new DriverOffer(UUID.randomUUID(), UUID.randomUUID(), tripId);
        offer2.reject();
        offerRepository.saveAndFlush(offer2);

        assertThat(offerRepository.findByTripId(tripId)).hasSize(2);
    }
}
