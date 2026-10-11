package com.ridebatching.dispatch;

import com.ridebatching.api.DomainConflictException;
import com.ridebatching.driver.*;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.*;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class DispatchService {
    private final DriverRepository drivers;
    private final DriverOfferRepository offers;
    private final DriverEligibilityPolicy eligibility;
    private final DriverProximityRanker ranker;
    private final RejectionCoolOffPolicy coolOff;
    private final TripRepository trips;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final Duration lifetime;
    private final int attemptLimit;
    @PersistenceContext private EntityManager entityManager;

    public DispatchService(DriverRepository drivers, DriverOfferRepository offers,
                           DriverEligibilityPolicy eligibility, DriverProximityRanker ranker,
                           RejectionCoolOffPolicy coolOff, TripRepository trips, MeterRegistry metrics,
                           @Qualifier("dispatchClock") Clock clock,
                           @Value("${dispatch.offer-lifetime:PT2M}") Duration lifetime,
                           @Value("${dispatch.max-offers-per-trip:3}") int attemptLimit) {
        if (lifetime.isZero() || lifetime.isNegative() || attemptLimit < 1 || attemptLimit > 100)
            throw new IllegalArgumentException("Dispatch requires a positive offer lifetime and 1..100 attempts");
        this.drivers = drivers; this.offers = offers; this.eligibility = eligibility;
        this.ranker = ranker; this.coolOff = coolOff; this.trips = trips; this.metrics = metrics;
        this.clock = clock; this.lifetime = lifetime; this.attemptLimit = attemptLimit;
    }

    /** Compatibility entry point for internal callers; real routes supply their own first pickup. */
    @Transactional
    public Optional<DriverOffer> dispatchTrip(UUID tripId, GeoPoint fallbackPickup) {
        Trip trip = lockTrip(tripId);
        requirePlanned(trip);
        return advanceLocked(trip, fallbackPickup, clock.instant());
    }

    @Transactional
    public DispatchView advanceTrip(UUID tripId) {
        Trip trip = lockTrip(tripId);
        Instant now = clock.instant();
        if (trip.getStatus() == TripStatus.PLANNED) advanceLocked(trip, pickup(trip), now);
        return view(trip, now);
    }

    @Transactional(noRollbackFor = OfferExpiredException.class)
    public DispatchView rejectOffer(UUID offerId) {
        DriverOffer reference = findOffer(offerId);
        Trip trip = lockTrip(reference.getTripId());
        DriverOffer offer = lockOffer(offerId);
        requirePlanned(trip);
        Instant now = clock.instant();
        if (offer.getStatus() == DriverOfferStatus.REJECTED) return view(trip, now);
        rejectExpiredResponse(trip, offer, now);
        offer.reject(now);
        metrics.counter("dispatch.rejections").increment();
        advanceLocked(trip, pickup(trip), now);
        return view(trip, now);
    }

    @Transactional(noRollbackFor = OfferExpiredException.class)
    public DispatchView acceptOffer(UUID offerId) {
        DriverOffer reference = findOffer(offerId);
        // All response paths lock trip -> offer -> driver. Driver locks serialize competing trips.
        Trip trip = lockTrip(reference.getTripId());
        DriverOffer offer = lockOffer(offerId);
        Driver driver = drivers.findByIdForUpdate(offer.getDriverId()).orElseThrow();
        entityManager.refresh(driver);
        if (offer.getStatus() == DriverOfferStatus.ACCEPTED) {
            if (trip.getStatus() == TripStatus.DISPATCHED && driver.getId().equals(trip.getDriverId())
                    && trip.getId().equals(driver.getActiveTripId())) return view(trip, clock.instant());
            throw new DomainConflictException("Accepted offer no longer has an active assignment");
        }
        requirePlanned(trip);
        Instant now = clock.instant();
        rejectExpiredResponse(trip, offer, now);
        if (!eligible(driver, trip, now)) throw new DomainConflictException("Driver is no longer eligible for this trip");
        offer.accept(now);
        trip.assignTo(driver.getId());
        driver.assignTrip(trip.getId());
        // Also close any outstanding offers imported from older application versions.
        for (DriverOffer other : offers.findByTripId(trip.getId())) {
            if (!other.getId().equals(offerId) && other.getStatus() == DriverOfferStatus.OFFERED) expire(other, now);
        }
        metrics.counter("dispatch.acceptances").increment();
        return view(trip, now);
    }

    private void rejectExpiredResponse(Trip trip, DriverOffer offer, Instant now) {
        if (offer.getStatus() == DriverOfferStatus.EXPIRED
                || (offer.getStatus() == DriverOfferStatus.OFFERED && offer.hasExpired(now))) {
            advanceLocked(trip, pickup(trip), now);
            throw new OfferExpiredException();
        }
    }

    private Optional<DriverOffer> advanceLocked(Trip trip, GeoPoint fallbackPickup, Instant now) {
        List<DriverOffer> history = history(trip.getId());
        DriverOffer active = null;
        for (DriverOffer reference : history) {
            if (reference.getStatus() != DriverOfferStatus.OFFERED) continue;
            DriverOffer offer = lockOffer(reference.getId());
            Driver driver = drivers.findById(offer.getDriverId()).orElseThrow();
            if (active == null && !offer.hasExpired(now) && eligible(driver, trip, now)) active = offer;
            else expire(offer, now);
        }
        if (active != null) return Optional.of(active);
        if (history.size() >= attemptLimit) return Optional.empty();
        Set<UUID> attempted = new HashSet<>();
        history.forEach(o -> attempted.add(o.getDriverId()));
        List<Driver> candidates = drivers.findAll().stream()
                .filter(d -> !attempted.contains(d.getId()) && eligible(d, trip, now)).toList();
        List<Driver> ranked = ranker.rankByProximity(candidates,
                trip.getStops().isEmpty() ? fallbackPickup : pickup(trip));
        if (ranked.isEmpty()) return Optional.empty();
        // Offers are provisional, not driver reservations. Acceptance rechecks under the driver lock.
        DriverOffer offer = offers.save(new DriverOffer(UUID.randomUUID(), ranked.get(0).getId(), trip.getId(), now, lifetime));
        metrics.counter("dispatch.offers").increment();
        return Optional.of(offer);
    }

    private boolean eligible(Driver driver, Trip trip, Instant now) {
        return eligibility.evaluate(driver).isEligible() && driver.getVehicleCapacity() >= trip.getPeakPassengers()
                && !coolOff.isExcludedByCoolOff(driver.getId(), trip.getId(), now);
    }

    private GeoPoint pickup(Trip trip) {
        return trip.getStops().stream().filter(s -> s.getType() == TripStopType.PICKUP)
                .findFirst().map(TripStop::getLocation).orElse(new GeoPoint(0, 0));
    }

    @Transactional(readOnly = true)
    public DispatchView getTrip(UUID id) {
        return view(trips.findById(id).orElseThrow(() -> new DispatchNotFoundException("Trip not found")), clock.instant());
    }

    @Transactional(readOnly = true)
    public Page<DispatchView> listTrips(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Page size must be 1..100");
        Instant now = clock.instant();
        return trips.findAll(PageRequest.of(page, size, Sort.by("createdAt").descending().and(Sort.by("id"))))
                .map(trip -> view(trip, now));
    }

    private DispatchView view(Trip trip, Instant now) {
        List<DriverOffer> history = history(trip.getId());
        DriverOffer active = history.stream().filter(o -> o.getStatus() == DriverOfferStatus.OFFERED).findFirst().orElse(null);
        DispatchOutcome outcome;
        if (trip.getStatus() == TripStatus.CANCELLED) outcome = DispatchOutcome.CANCELLED;
        else if (trip.getStatus() == TripStatus.DISPATCHED) outcome = DispatchOutcome.ASSIGNED;
        else if (trip.getStatus() != TripStatus.PLANNED) outcome = DispatchOutcome.TRIP_NOT_PLANNED;
        else if (active != null) outcome = active.hasExpired(now) ? DispatchOutcome.OFFER_EXPIRED : DispatchOutcome.OFFERED;
        else if (history.size() >= attemptLimit) outcome = DispatchOutcome.ATTEMPT_LIMIT_REACHED;
        else {
            Set<UUID> attempted = new HashSet<>();
            history.forEach(o -> attempted.add(o.getDriverId()));
            // Ignore temporary cool-off here: an attempted driver is never offered this trip again.
            boolean availableUntried = drivers.findAll().stream().anyMatch(d -> !attempted.contains(d.getId())
                    && eligibility.evaluate(d).isEligible() && d.getVehicleCapacity() >= trip.getPeakPassengers());
            outcome = availableUntried ? DispatchOutcome.READY_FOR_RETRY
                    : history.isEmpty() ? DispatchOutcome.NO_ELIGIBLE_DRIVER : DispatchOutcome.DRIVERS_EXHAUSTED;
        }
        return new DispatchView(trip.getId(), trip.getStatus(), trip.getDriverId(), trip.getPeakPassengers(), outcome,
                active == null ? null : active.getId(), history.size(), attemptLimit,
                history.stream().map(o -> new DispatchView.Offer(o.getId(), o.getDriverId(), o.getStatus(),
                        o.getCreatedAt(), o.getExpiresAt(), o.getUpdatedAt())).toList(),
                trip.getStops().stream().map(s -> new DispatchView.Stop(s.getSequenceOrder(), s.getBookingId(), s.getType(), s.getLocation())).toList());
    }

    private List<DriverOffer> history(UUID tripId) {
        return offers.findByTripId(tripId).stream()
                .sorted(Comparator.comparing(DriverOffer::getCreatedAt).thenComparing(DriverOffer::getId)).toList();
    }
    private DriverOffer findOffer(UUID id) {
        return offers.findById(id).orElseThrow(() -> new DispatchNotFoundException("Offer not found"));
    }
    private DriverOffer lockOffer(UUID id) {
        DriverOffer offer = offers.findByIdForUpdate(id).orElseThrow(() -> new DispatchNotFoundException("Offer not found"));
        entityManager.refresh(offer);
        return offer;
    }
    private Trip lockTrip(UUID id) {
        Trip locked = trips.findByIdForUpdate(id).orElseThrow(() -> new DispatchNotFoundException("Trip not found"));
        // Reload after the lock; cascading refresh can encounter concurrently deleted stops.
        entityManager.detach(locked);
        return trips.findById(id).orElseThrow();
    }
    private void expire(DriverOffer offer, Instant now) {
        offer.expire(now);
        metrics.counter("dispatch.expirations").increment();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void cancelTripAssignments(UUID tripId) {
        Trip trip = lockTrip(tripId);
        if (trip.getStatus() != TripStatus.CANCELLED) throw new DomainConflictException("Only cancelled trips can release assignments");
        Instant now = clock.instant();
        for (DriverOffer reference : offers.findByTripId(trip.getId())) {
            DriverOffer offer = lockOffer(reference.getId());
            if (offer.getStatus() == DriverOfferStatus.OFFERED) expire(offer, now);
        }
        if (trip.getDriverId() != null) {
            Driver driver = drivers.findByIdForUpdate(trip.getDriverId()).orElseThrow();
            entityManager.refresh(driver);
            if (trip.getId().equals(driver.getActiveTripId())) driver.completeTrip();
        }
    }
    private void requirePlanned(Trip trip) {
        if (trip.getStatus() != TripStatus.PLANNED) throw new DomainConflictException("Only planned trips can receive new driver assignments");
    }
}
