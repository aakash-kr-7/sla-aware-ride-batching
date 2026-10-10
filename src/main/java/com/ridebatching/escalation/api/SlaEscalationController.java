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

@RestController
@RequestMapping("/api/v1/escalations")
@Tag(name = "Approver (Escalations)", description = "Endpoints for approvers to view and manage SLA escalations")
public class SlaEscalationController {

    private final SlaEscalationRepository escalationRepository;

    public SlaEscalationController(SlaEscalationRepository escalationRepository) {
        this.escalationRepository = escalationRepository;
    }

    @GetMapping("/open")
    @PreAuthorize("hasRole('ADMIN') or hasRole('APPROVER')")
    public List<SlaEscalation> getOpenEscalations() {
        return escalationRepository.findByStatus(SlaEscalationStatus.OPEN);
    }
}
