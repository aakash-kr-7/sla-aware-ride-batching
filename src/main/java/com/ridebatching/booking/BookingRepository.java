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
    
    Page<Booking> findByEmployeeId(UUID employeeId, Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
        value = "SELECT * FROM bookings WHERE status = 'PENDING' FOR UPDATE SKIP LOCKED", 
        nativeQuery = true)
    java.util.List<Booking> findPendingBookingsForBatching();
}
