package com.ridebatching.dispatch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface DriverOfferRepository extends JpaRepository<DriverOffer, UUID> {
    
    List<DriverOffer> findByTripId(UUID tripId);
    
    // For rejection cool-off, we can look for recent rejections
    List<DriverOffer> findByDriverIdAndTripIdAndStatusAndCreatedAtAfter(UUID driverId, UUID tripId, DriverOfferStatus status, Instant since);
}
