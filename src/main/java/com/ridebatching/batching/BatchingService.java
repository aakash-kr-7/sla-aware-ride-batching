package com.ridebatching.batching;

import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingRepository;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.trip.Trip;
import com.ridebatching.trip.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class BatchingService {

    private static final Logger log = LoggerFactory.getLogger(BatchingService.class);

    private final BookingRepository bookingRepository;
    private final TripRepository tripRepository;
    private final BatchPlanner batchPlanner;
    private final jakarta.persistence.EntityManager entityManager;
    private final MeterRegistry meterRegistry;
    private final BatchClaimPolicy claimPolicy;
    private final RequestClusterer clusterer;
    private final Clock clock;

    public BatchingService(BookingRepository bookingRepository, TripRepository tripRepository, BatchPlanner batchPlanner,
                           jakarta.persistence.EntityManager entityManager, MeterRegistry meterRegistry,
                           BatchClaimPolicy claimPolicy, RequestClusterer clusterer,
                           @org.springframework.beans.factory.annotation.Qualifier("batchingClock") Clock clock) {
        this.bookingRepository = bookingRepository;
        this.tripRepository = tripRepository;
        this.batchPlanner = batchPlanner;
        this.entityManager = entityManager;
        this.meterRegistry = meterRegistry;
        this.claimPolicy = claimPolicy;
        this.clusterer = clusterer;
        this.clock = clock;
    }

    public record BatchingResult(int bookingsConsidered, int tripsFormed, int bookingsLeftPending) {}

    /**
     * Executes one cycle of batch formation.
     * Uses a strong transactional boundary and PostgreSQL's SELECT ... FOR UPDATE SKIP LOCKED 
     * to safely claim pending bookings without blocking concurrent batching workers.
     * This avoids distributed locks and ensures that partially planned trips are completely rolled back
     * if the database commit fails.
     */
    @Transactional
    public BatchingResult formBatches() {
        Timer.Sample timerSample = Timer.start(meterRegistry);
        try {
            Instant routeStartTime = clock.instant();
            // Eligibility and route estimates share one cycle timestamp.
            List<Booking> pendingBookings = bookingRepository.claimEligibleBookings(routeStartTime,
                    claimPolicy.closedWindowsBefore(routeStartTime), claimPolicy.urgentDeadlineThrough(routeStartTime),
                    claimPolicy.maxBookingsPerCycle());
        
            if (pendingBookings.isEmpty()) {
                return new BatchingResult(0, 0, 0);
            }
        
            // Preserve source windows even when a backlog spans several closed buckets.
            // Claim ordering gives windows with the earliest claimed deadlines first.
            Map<Instant, List<Booking>> windows = new LinkedHashMap<>();
            for (Booking booking : pendingBookings) {
                Instant windowStart = claimPolicy.windowFor(booking.getCreatedAt()).start();
                windows.computeIfAbsent(windowStart, ignored -> new ArrayList<>()).add(booking);
            }
            List<Trip> trips = new ArrayList<>();
            int clusterCount = 0;
            for (List<Booking> window : windows.values()) {
                for (List<Booking> cluster : clusterer.clusterBookings(window)) {
                    clusterCount++;
                    trips.addAll(batchPlanner.planBatch(cluster, routeStartTime));
                }
            }
        
            // 3. Persist trips (cascades to TripStop)
            tripRepository.saveAll(trips);
        
            // 4. Update booking statuses
            int assignedCount = 0;
            for (Booking booking : pendingBookings) {
                if (booking.getTripId() != null) {
                    booking.setStatus(BookingStatus.CONFIRMED);
                    assignedCount++;
                }
            }
        
            int pendingRemaining = pendingBookings.size() - assignedCount;
        
            meterRegistry.counter("batching.trips_formed").increment(trips.size());
        
            log.info("Batching cycle completed: considered={}, windows={}, clusters={}, tripsFormed={}, assigned={}, leftPending={}",
                    pendingBookings.size(), windows.size(), clusterCount, trips.size(), assignedCount, pendingRemaining);
                
            return new BatchingResult(pendingBookings.size(), trips.size(), pendingRemaining);
        } finally {
            timerSample.stop(meterRegistry.timer("batching.duration"));
        }
    }

    /**
     * Attempts to insert a late-arriving booking into an existing planned trip.
     */
    @Transactional
    public boolean attemptLateInsertion(Booking booking) {
        // Share the booking claim protocol with batching/cancellation before locking a trip.
        if (entityManager.contains(booking)) {
            entityManager.detach(booking);
        }
        Booking claimed = bookingRepository.findByIdForUpdate(booking.getId()).orElse(null);
        if (claimed == null || claimed.getStatus() != BookingStatus.PENDING || claimed.getTripId() != null) {
            return false;
        }
        booking = claimed;
        // Find existing planned trips
        List<Trip> candidateTrips = tripRepository.findByStatusIn(List.of(com.ridebatching.trip.TripStatus.PLANNED));
        if (candidateTrips.isEmpty()) {
            return false;
        }

        List<UUID> bookingIds = candidateTrips.stream()
                .flatMap(t -> t.getStops().stream())
                .map(com.ridebatching.trip.TripStop::getBookingId)
                .toList();

        java.util.Map<UUID, Booking> currentBookings = new java.util.HashMap<>();
        for (Booking b : bookingRepository.findAllById(bookingIds)) {
            currentBookings.put(b.getId(), b);
        }
        currentBookings.put(booking.getId(), booking);

        com.ridebatching.fleet.Vehicle dummyVehicle = new com.ridebatching.fleet.Vehicle(UUID.randomUUID(), batchPlanner.getDefaultVehicleCapacity());

        BatchPlanner.InsertionResult initialResult = batchPlanner.findBestTripForInsertion(
                candidateTrips, booking, currentBookings, Instant.now(), dummyVehicle);

        if (initialResult != null) {
            // Reload the aggregate after acquiring its row lock. Cascaded REFRESH of the
            // old collection can fail if a concurrent mutation deleted those stop rows.
            entityManager.detach(initialResult.trip());
            Trip lockedTrip = tripRepository.findByIdForUpdate(initialResult.trip().getId()).orElse(null);
            if (lockedTrip != null && lockedTrip.getStatus() == com.ridebatching.trip.TripStatus.PLANNED) {
                currentBookings.clear();
                List<UUID> lockedBookingIds = lockedTrip.getStops().stream()
                        .map(com.ridebatching.trip.TripStop::getBookingId).distinct().toList();
                for (Booking rider : bookingRepository.findAllById(lockedBookingIds)) {
                    currentBookings.put(rider.getId(), rider);
                }
                currentBookings.put(booking.getId(), booking);
                
                // Re-verify after locking
                BatchPlanner.InsertionResult finalResult = batchPlanner.findBestTripForInsertion(
                        List.of(lockedTrip), booking, currentBookings, Instant.now(), dummyVehicle);
                
                if (finalResult != null) {
                    lockedTrip.assignRoute(finalResult.route());
                    tripRepository.save(lockedTrip);
                    
                    booking.setTripId(lockedTrip.getId());
                    booking.setStatus(BookingStatus.CONFIRMED);
                    bookingRepository.save(booking);
                    meterRegistry.counter("batching.late_insertion", "result", "success").increment();
                    log.info("Late insertion result: success for booking");
                    return true;
                }
            }
        }

        meterRegistry.counter("batching.late_insertion", "result", "failure").increment();
        log.info("Late insertion result: failure for booking");
        return false;
    }
}
