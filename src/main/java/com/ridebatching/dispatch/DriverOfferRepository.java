package com.ridebatching.dispatch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface DriverOfferRepository extends JpaRepository<DriverOffer, UUID> {
    
    List<DriverOffer> findByTripId(UUID tripId);
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM DriverOffer o WHERE o.id = :id")
    Optional<DriverOffer> findByIdForUpdate(@Param("id") UUID id);

    List<DriverOffer> findByDriverIdAndTripIdAndStatusAndUpdatedAtAfter(UUID driverId, UUID tripId, DriverOfferStatus status, Instant since);
}
