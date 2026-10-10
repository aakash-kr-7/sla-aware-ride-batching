package com.ridebatching.batching.api;

import com.ridebatching.batching.BatchingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/admin/batching")
public class BatchingAdminController {

    private final BatchingService batchingService;

    public BatchingAdminController(BatchingService batchingService) {
        this.batchingService = batchingService;
    }

    /**
     * Administrative endpoint to manually trigger a single batching cycle.
     * Primarily used for testing, demonstration, or operational intervention.
     */
    @PostMapping("/trigger")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BatchingService.BatchingResult> triggerBatching() {
        BatchingService.BatchingResult result = batchingService.formBatches();
        return ResponseEntity.ok(result);
    }
}
