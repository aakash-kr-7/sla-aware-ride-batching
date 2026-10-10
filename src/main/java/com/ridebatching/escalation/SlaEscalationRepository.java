package com.ridebatching.escalation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface SlaEscalationRepository extends JpaRepository<SlaEscalation, UUID> {
    List<SlaEscalation> findByStatus(SlaEscalationStatus status);
}
