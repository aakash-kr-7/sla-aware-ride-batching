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

@Service
public class BatchingService {

    private static final Logger log = LoggerFactory.getLogger(BatchingService.class);

    private final BookingRepository bookingRepository;
    private final TripRepository tripRepository;
    private final BatchPlanner batchPlanner;

    public BatchingService(BookingRepository bookingRepository, TripRepository tripRepository, BatchPlanner batchPlanner) {
        this.bookingRepository = bookingRepository;
        this.tripRepository = tripRepository;
        this.batchPlanner = batchPlanner;
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
        
        log.info("Batching cycle completed: considered={}, tripsFormed={}, assigned={}, leftPending={}",
                pendingBookings.size(), trips.size(), assignedCount, pendingRemaining);
                
        return new BatchingResult(pendingBookings.size(), trips.size(), pendingRemaining);
    }
}
