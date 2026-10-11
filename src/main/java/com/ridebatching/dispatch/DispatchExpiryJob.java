package com.ridebatching.dispatch;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;

/** Only scans due offers; each trip advances in its own service transaction. */
@Component
public class DispatchExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(DispatchExpiryJob.class);
    private final DriverOfferRepository offers;
    private final DispatchService dispatch;
    private final Clock clock;
    private final boolean enabled;
    private final int cycleLimit;

    public DispatchExpiryJob(DriverOfferRepository offers, DispatchService dispatch,
                             @Qualifier("dispatchClock") Clock clock,
                             @Value("${dispatch.expiry-scheduling.enabled:true}") boolean enabled,
                             @Value("${dispatch.expiry-scheduling.max-trips-per-cycle:100}") int cycleLimit) {
        if (cycleLimit < 1 || cycleLimit > 1000) throw new IllegalArgumentException("Expiry cycle limit must be 1..1000");
        this.offers = offers; this.dispatch = dispatch; this.clock = clock;
        this.enabled = enabled; this.cycleLimit = cycleLimit;
    }

    @Scheduled(fixedDelayString = "${dispatch.expiry-scheduling.interval-ms:5000}")
    public void tick() { if (enabled) advanceExpiredOffers(); }

    public int advanceExpiredOffers() {
        int processed = 0;
        for (var tripId : offers.findTripsWithExpiredOffers(clock.instant(), cycleLimit)) {
            try {
                dispatch.advanceTrip(tripId);
                processed++;
            } catch (RuntimeException failure) {
                log.error("Could not advance expired offer for trip {}", tripId, failure);
            }
        }
        return processed;
    }
}
