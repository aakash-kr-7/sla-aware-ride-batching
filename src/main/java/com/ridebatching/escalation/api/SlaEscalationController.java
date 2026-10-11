package com.ridebatching.escalation.api;

import com.ridebatching.escalation.SlaEscalation;
import com.ridebatching.escalation.SlaEscalationRepository;
import com.ridebatching.escalation.SlaEscalationStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controller for SLA Escalations.
 * This provides the limited capability for APPROVERs and ADMINs
 * to inspect open escalations where SLAs are becoming unsafe.
 */
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/v1/escalations")
@Tag(name = "Approver (Escalations)", description = "Read access to open SLA escalations")
public class SlaEscalationController {

    private final SlaEscalationRepository escalationRepository;

    public SlaEscalationController(SlaEscalationRepository escalationRepository) {
        this.escalationRepository = escalationRepository;
    }

    @GetMapping("/open")
    @Operation(summary = "Read existing open escalation records", description = "Admin or approver only. Read-only list of previously stored OPEN records; an empty list is possible. Batching currently does not create escalations automatically, and no escalation-resolution operation is exposed.")
    @PreAuthorize("hasRole('ADMIN') or hasRole('APPROVER')")
    public List<SlaEscalation> getOpenEscalations() {
        return escalationRepository.findByStatus(SlaEscalationStatus.OPEN);
    }
}
