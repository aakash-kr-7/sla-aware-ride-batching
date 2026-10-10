package com.ridebatching.escalation;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Column;
import java.util.UUID;
import java.time.Instant;

@Entity
@Table(name = "sla_escalation")
public class SlaEscalation {
    @Id
    private UUID id;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Column(name = "reference_type", nullable = false)
    private String referenceType;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SlaEscalationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SlaEscalation() {}

    public SlaEscalation(UUID id, UUID referenceId, String referenceType, String reason, SlaEscalationStatus status) {
        this.id = id;
        this.referenceId = referenceId;
        this.referenceType = referenceType;
        this.reason = reason;
        this.status = status;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getReferenceId() { return referenceId; }
    public String getReferenceType() { return referenceType; }
    public String getReason() { return reason; }
    public SlaEscalationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void resolve() {
        this.status = SlaEscalationStatus.RESOLVED;
        this.updatedAt = Instant.now();
    }
}
