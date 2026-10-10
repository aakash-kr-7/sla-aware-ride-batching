package com.ridebatching.batching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.batching.scheduling.enabled", havingValue = "true", matchIfMissing = false)
public class ScheduledBatchingTrigger {

    private static final Logger log = LoggerFactory.getLogger(ScheduledBatchingTrigger.class);

    private final BatchingService batchingService;

    public ScheduledBatchingTrigger(BatchingService batchingService) {
        this.batchingService = batchingService;
    }

    /**
     * Periodically triggers the batch formation logic.
     * The fixedDelay ensures that overlapping executions on the same application instance 
     * cannot occur (the next cycle waits for the current one to finish before starting the delay timer).
     * Competing application instances will safely race using PostgreSQL SKIP LOCKED.
     */
    @Scheduled(fixedDelayString = "${app.batching.scheduling.interval-ms:5000}")
    public void runBatchingCycle() {
        log.debug("Scheduled batching cycle triggered");
        try {
            BatchingService.BatchingResult result = batchingService.formBatches();
            if (result.bookingsConsidered() > 0) {
                log.info("Scheduled batching processed {} bookings into {} trips ({} left pending)",
                        result.bookingsConsidered(), result.tripsFormed(), result.bookingsLeftPending());
            }
        } catch (Exception e) {
            log.error("Scheduled batching cycle failed", e);
        }
    }
}
