package com.ridebatching.dispatch;

import com.ridebatching.driver.Driver;
import com.ridebatching.driver.DriverEligibilityPolicy;
import com.ridebatching.driver.DriverProximityRanker;
import com.ridebatching.driver.DriverRepository;
import com.ridebatching.geo.GeoPoint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DispatchService {

    private final DriverRepository driverRepository;
    private final DriverOfferRepository offerRepository;
    private final DriverEligibilityPolicy eligibilityPolicy;
    private final DriverProximityRanker ranker;
    private final RejectionCoolOffPolicy coolOffPolicy;

    public DispatchService(DriverRepository driverRepository,
                           DriverOfferRepository offerRepository,
                           DriverEligibilityPolicy eligibilityPolicy,
                           DriverProximityRanker ranker,
                           RejectionCoolOffPolicy coolOffPolicy) {
        this.driverRepository = driverRepository;
        this.offerRepository = offerRepository;
        this.eligibilityPolicy = eligibilityPolicy;
        this.ranker = ranker;
        this.coolOffPolicy = coolOffPolicy;
    }

    @Transactional
    public Optional<DriverOffer> dispatchTrip(UUID tripId, GeoPoint firstPickupLocation) {
        List<Driver> allDrivers = driverRepository.findAll();
        
        Instant now = Instant.now();

        List<Driver> eligibleDrivers = allDrivers.stream()
                .filter(d -> eligibilityPolicy.evaluate(d).isEligible())
                .filter(d -> !coolOffPolicy.isExcludedByCoolOff(d.getId(), tripId, now))
                .toList();

        List<Driver> rankedDrivers = ranker.rankByProximity(eligibleDrivers, firstPickupLocation);

        if (rankedDrivers.isEmpty()) {
            return Optional.empty();
        }

        Driver selectedDriver = rankedDrivers.get(0);
        
        DriverOffer offer = new DriverOffer(UUID.randomUUID(), selectedDriver.getId(), tripId);
        offerRepository.save(offer);
        
        return Optional.of(offer);
    }
    
    @Transactional
    public void rejectOffer(UUID offerId) {
        DriverOffer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found"));
        offer.reject();
        offerRepository.save(offer);
    }
}
