package com.ridebatching.dispatch;

import com.ridebatching.driver.Driver;
import com.ridebatching.driver.DriverEligibilityPolicy;
import com.ridebatching.driver.DriverProximityRanker;
import com.ridebatching.driver.DriverRepository;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.Trip;
import com.ridebatching.trip.TripRepository;
import com.ridebatching.trip.TripStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
    @PersistenceContext
    private EntityManager entityManager;

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
        Trip trip = lockTrip(tripId);
        requirePlanned(trip);
        List<Driver> allDrivers = driverRepository.findAll();
        
        Instant now = Instant.now();

        List<Driver> eligibleDrivers = allDrivers.stream()
                .filter(d -> eligibilityPolicy.evaluate(d).isEligible())
                .filter(d -> d.getVehicleCapacity() >= trip.getPeakPassengers())
                .filter(d -> !coolOffPolicy.isExcludedByCoolOff(d.getId(), tripId, now))
                .toList();

        List<Driver> rankedDrivers = ranker.rankByProximity(eligibleDrivers, firstPickupLocation);

        if (rankedDrivers.isEmpty()) {
            return Optional.empty();
        }

        Driver selectedDriver = driverRepository.findByIdForUpdate(rankedDrivers.get(0).getId()).orElseThrow();
        entityManager.refresh(selectedDriver);
        if (!eligibilityPolicy.evaluate(selectedDriver).isEligible()
                || selectedDriver.getVehicleCapacity() < trip.getPeakPassengers()
                || coolOffPolicy.isExcludedByCoolOff(selectedDriver.getId(), tripId, Instant.now())) {
            return Optional.empty();
        }
        
        DriverOffer offer = new DriverOffer(UUID.randomUUID(), selectedDriver.getId(), tripId);
        offerRepository.save(offer);
        
        meterRegistry.counter("dispatch.offers").increment();
        
        return Optional.of(offer);
    }
    
    @Transactional
    public void rejectOffer(UUID offerId) {
        DriverOffer offer = offerRepository.findByIdForUpdate(offerId)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found"));
        entityManager.refresh(offer);
        offer.reject();
        offerRepository.save(offer);
        
        meterRegistry.counter("dispatch.rejections").increment();
        log.info("Driver rejected offer");
    }

    @Transactional
    public void acceptOffer(UUID offerId) {
        DriverOffer reference = offerRepository.findById(offerId)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found"));
        // All assignment paths use trip -> offer -> driver order. Different trips still share the driver lock.
        Trip trip = lockTrip(reference.getTripId());
        DriverOffer offer = offerRepository.findByIdForUpdate(offerId).orElseThrow();
        entityManager.refresh(offer);
        Driver driver = driverRepository.findByIdForUpdate(offer.getDriverId())
                .orElseThrow(() -> new IllegalArgumentException("Driver not found"));
        entityManager.refresh(driver);
        if (offer.getStatus() == DriverOfferStatus.ACCEPTED) {
            if (trip.getStatus() == TripStatus.DISPATCHED && driver.getId().equals(trip.getDriverId())
                    && trip.getId().equals(driver.getActiveTripId())) return;
            throw new com.ridebatching.api.DomainConflictException("Accepted offer no longer has an active assignment");
        }
        requirePlanned(trip);
        if (!eligibilityPolicy.evaluate(driver).isEligible()
                || driver.getVehicleCapacity() < trip.getPeakPassengers()
                || coolOffPolicy.isExcludedByCoolOff(driver.getId(), trip.getId(), Instant.now())) {
            throw new com.ridebatching.api.DomainConflictException("Driver is no longer eligible for this trip");
        }
        offer.accept();
        trip.assignTo(driver.getId());
        driver.assignTrip(trip.getId());
        
        log.info("Driver accepted offer");
    }

    private Trip lockTrip(UUID id) {
        Trip locked = tripRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Trip not found"));
        // Reload the aggregate after the row lock; cascading refresh can encounter already-deleted stops.
        entityManager.detach(locked);
        return tripRepository.findById(id).orElseThrow();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void cancelTripAssignments(UUID tripId) {
        // Join cancellation's transaction and lock the persisted trip before releasing any assignments.
        Trip trip = lockTrip(tripId);
        if (trip.getStatus() != TripStatus.CANCELLED) {
            throw new com.ridebatching.api.DomainConflictException("Only cancelled trips can release assignments");
        }
        for (DriverOffer reference : offerRepository.findByTripId(trip.getId())) {
            DriverOffer offer = offerRepository.findByIdForUpdate(reference.getId()).orElseThrow();
            entityManager.refresh(offer);
            if (offer.getStatus() == DriverOfferStatus.OFFERED) offer.expire();
        }
        if (trip.getDriverId() != null) {
            Driver driver = driverRepository.findByIdForUpdate(trip.getDriverId()).orElseThrow();
            entityManager.refresh(driver);
            if (trip.getId().equals(driver.getActiveTripId())) driver.completeTrip();
        }
    }

    private void requirePlanned(Trip trip) {
        if (trip.getStatus() != TripStatus.PLANNED) {
            throw new com.ridebatching.api.DomainConflictException("Only planned trips can receive new driver assignments");
        }
    }
}
