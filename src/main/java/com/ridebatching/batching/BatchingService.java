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

    public BatchingService(BookingRepository bookingRepository, TripRepository tripRepository, BatchPlanner batchPlanner, jakarta.persistence.EntityManager entityManager, MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.tripRepository = tripRepository;
        this.batchPlanner = batchPlanner;
        this.entityManager = entityManager;
        this.meterRegistry = meterRegistry;
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
        // 1. Claim eligible pending bookings exclusively for this transaction
        List<Booking> pendingBookings = bookingRepository.findPendingBookingsForBatching();
        
        if (pendingBookings.isEmpty()) {
            return new BatchingResult(0, 0, 0);
        }
        
        Instant routeStartTime = Instant.now();
        
        // 2. Plan trips using the deterministic greedy batch planner
        List<Trip> trips = batchPlanner.planBatch(pendingBookings, routeStartTime);
        
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
        
        log.info("Batching cycle completed: considered={}, tripsFormed={}, assigned={}, leftPending={}",
                pendingBookings.size(), trips.size(), assignedCount, pendingRemaining);
                
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
            Trip lockedTrip = tripRepository.findByIdForUpdate(initialResult.trip().getId()).orElse(null);
            if (lockedTrip != null && lockedTrip.getStatus() == com.ridebatching.trip.TripStatus.PLANNED) {
                // Refresh entity to clear L1 cache state and load latest stops from DB
                entityManager.refresh(lockedTrip);
                
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
