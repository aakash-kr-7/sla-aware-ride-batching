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
    @Operation(summary = "Plan trips for pending bookings", description = "Admin only; no request body. Processes claimed pending requests, checking capacity, pickup deadlines and detours. Returns bookingsConsidered, tripsFormed and bookingsLeftPending. Zero considered means no pending rows were claimed. Retrieve a booking afterward to inspect its status/tripId. This operation does not dispatch drivers or use runtime window/direction clustering.")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BatchingService.BatchingResult> triggerBatching() {
        BatchingService.BatchingResult result = batchingService.formBatches();
        return ResponseEntity.ok(result);
    }
}
