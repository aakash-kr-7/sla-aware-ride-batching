package com.ridebatching.batching.api;

import com.ridebatching.batching.BatchingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/admin/batching")
@Tag(name = "Admin (Batching)", description = "Administrative endpoints for triggering and managing the batching process")
public class BatchingAdminController {

    private final BatchingService batchingService;

    public BatchingAdminController(BatchingService batchingService) {
        this.batchingService = batchingService;
    }

    /**
     * Administrative endpoint to manually trigger a single batching cycle.
     * Used for testing or explicit operational intervention.
     */
    @PostMapping("/trigger")
    @Operation(summary = "Plan trips and offer eligible drivers", description = "Admin only; no request body. Claims a bounded set from closed UTC windows or urgent requests (default 100 claims, five-minute urgency guard), then groups by source window, active pickup zone and direction before checking capacity, pickup deadlines and detours. Each new trip attempts its initial driver offer in the same transaction. No eligible driver leaves it PLANNED. Manual calls do not force an open window closed. Returns bookingsConsidered, tripsFormed and bookingsLeftPending for claimed rows only; zero considered can coexist with collecting or expired bookings. Expired requests remain pending and excluded from planning claims; a separately bounded pass persists one OPEN missed-promise escalation per booking/reason. Those recovery rows are not included in cycle counts. Use Admin (Dispatch simulation) to list routes/offers or retrieve a booking to find its tripId.")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BatchingService.BatchingResult> triggerBatching() {
        BatchingService.BatchingResult result = batchingService.formBatches();
        return ResponseEntity.ok(result);
    }
}
