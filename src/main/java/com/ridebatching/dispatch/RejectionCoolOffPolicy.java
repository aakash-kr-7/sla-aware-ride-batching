package com.ridebatching.dispatch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class RejectionCoolOffPolicy {

    private final DriverOfferRepository offerRepository;
    private final Duration coolOffDuration;

    public RejectionCoolOffPolicy(
            DriverOfferRepository offerRepository,
            @Value("${dispatch.cool-off.duration:PT5M}") Duration coolOffDuration) {
        this.offerRepository = offerRepository;
        this.coolOffDuration = coolOffDuration;
    }

    public boolean isExcludedByCoolOff(UUID driverId, UUID tripId, Instant now) {
        Instant threshold = now.minus(coolOffDuration);
        
        List<DriverOffer> recentRejections = offerRepository
                .findByDriverIdAndTripIdAndStatusAndCreatedAtAfter(
                        driverId, tripId, DriverOfferStatus.REJECTED, threshold
                );
                
        return !recentRejections.isEmpty();
    }
}
