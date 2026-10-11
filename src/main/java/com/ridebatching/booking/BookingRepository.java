package com.ridebatching.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {
    Optional<Booking> findByDeduplicationKey(String deduplicationKey);

    Optional<Booking> findByDeduplicationKeyAndStatusNot(String key, BookingStatus status);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    // ON CONFLICT avoids aborting the surrounding creation/insertion transaction.
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = """
        INSERT INTO bookings (id, employee_id, pickup_latitude, pickup_longitude,
            dropoff_latitude, dropoff_longitude, slot_timestamp, status,
            pickup_sla_deadline, created_at, deduplication_key)
        VALUES (:#{#booking.id}, :#{#booking.employee.id},
            :#{#booking.pickupLocation.latitude()}, :#{#booking.pickupLocation.longitude()},
            :#{#booking.dropoffLocation.latitude()}, :#{#booking.dropoffLocation.longitude()},
            :#{#booking.slotTimestamp}, 'PENDING', :#{#booking.pickupSlaDeadline},
            :#{#booking.createdAt}, :#{#booking.deduplicationKey})
        ON CONFLICT (deduplication_key) WHERE status <> 'CANCELLED' DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@org.springframework.data.repository.query.Param("booking") Booking booking);
    
    Page<Booking> findByEmployeeId(UUID employeeId, Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
        value = "SELECT * FROM bookings WHERE status = 'PENDING' FOR UPDATE SKIP LOCKED", 
        nativeQuery = true)
    java.util.List<Booking> findPendingBookingsForBatching();

    // The transaction holds these booking locks through route persistence and confirmation.
    @org.springframework.data.jpa.repository.Query(value = """
        SELECT * FROM bookings
        WHERE status = 'PENDING' AND trip_id IS NULL
          AND created_at <= :now AND pickup_sla_deadline >= :now
          AND (created_at < :closedWindowsBefore OR pickup_sla_deadline <= :urgentDeadlineThrough)
        ORDER BY pickup_sla_deadline, created_at, id
        LIMIT :claimLimit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    java.util.List<Booking> claimEligibleBookings(
            @org.springframework.data.repository.query.Param("now") java.time.Instant now,
            @org.springframework.data.repository.query.Param("closedWindowsBefore") java.time.Instant closedWindowsBefore,
            @org.springframework.data.repository.query.Param("urgentDeadlineThrough") java.time.Instant urgentDeadlineThrough,
            @org.springframework.data.repository.query.Param("claimLimit") int claimLimit);
}
