package com.ridebatching.dispatch;

import com.ridebatching.driver.Driver;
import com.ridebatching.driver.DriverEligibilityPolicy;
import com.ridebatching.driver.DriverProximityRanker;
import com.ridebatching.driver.DriverRepository;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.Trip;
import com.ridebatching.trip.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private final DriverRepository driverRepository;
    private final DriverOfferRepository offerRepository;
    private final DriverEligibilityPolicy eligibilityPolicy;
    private final DriverProximityRanker ranker;
    private final RejectionCoolOffPolicy coolOffPolicy;
    private final TripRepository tripRepository;
    private final MeterRegistry meterRegistry;

    public DispatchService(DriverRepository driverRepository,
                           DriverOfferRepository offerRepository,
                           DriverEligibilityPolicy eligibilityPolicy,
                           DriverProximityRanker ranker,
                           RejectionCoolOffPolicy coolOffPolicy,
                           TripRepository tripRepository,
                           MeterRegistry meterRegistry) {
        this.driverRepository = driverRepository;
        this.offerRepository = offerRepository;
        this.eligibilityPolicy = eligibilityPolicy;
        this.ranker = ranker;
        this.coolOffPolicy = coolOffPolicy;
        this.tripRepository = tripRepository;
        this.meterRegistry = meterRegistry;
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
        
        meterRegistry.counter("dispatch.offers").increment();
        
        return Optional.of(offer);
    }
    
    @Transactional
    public void rejectOffer(UUID offerId) {
        DriverOffer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found"));
        offer.reject();
        offerRepository.save(offer);
        
        meterRegistry.counter("dispatch.rejections").increment();
        log.info("Driver rejected offer");
    }

    @Transactional
    public void acceptOffer(UUID offerId) {
        DriverOffer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found"));

        // Accept the offer first (will throw if not OFFERED)
        offer.accept();
        offerRepository.save(offer);

        // Lock the trip to prevent concurrent assignments
        Trip trip = tripRepository.findByIdForUpdate(offer.getTripId())
                .orElseThrow(() -> new IllegalArgumentException("Trip not found"));

        // Transition trip state (will throw if already DISPATCHED or not PLANNED)
        trip.assignTo(offer.getDriverId());
        tripRepository.save(trip);

        // Assign the driver
        Driver driver = driverRepository.findById(offer.getDriverId())
                .orElseThrow(() -> new IllegalArgumentException("Driver not found"));
        driver.assignTrip(trip.getId());
        driverRepository.save(driver);
        
        log.info("Driver accepted offer");
    }
}
