package com.ridebatching.escalation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface SlaEscalationRepository extends JpaRepository<SlaEscalation, UUID> {
    List<SlaEscalation> findByStatus(SlaEscalationStatus status);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = """
            INSERT INTO sla_escalation (id, reference_id, reference_type, reason, status, created_at, updated_at)
            VALUES (:id, :bookingId, 'BOOKING', :reason, 'OPEN', :now, :now)
            ON CONFLICT (reference_type, reference_id, reason) WHERE status='OPEN' DO NOTHING
            """, nativeQuery = true)
    int openBookingIssue(@org.springframework.data.repository.query.Param("id") UUID id,
                         @org.springframework.data.repository.query.Param("bookingId") UUID bookingId,
                         @org.springframework.data.repository.query.Param("reason") String reason,
                         @org.springframework.data.repository.query.Param("now") java.time.Instant now);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = """
            UPDATE sla_escalation SET status='RESOLVED', updated_at=:now
            WHERE reference_type='BOOKING' AND reference_id=:bookingId AND status='OPEN' AND reason=:reason
            """, nativeQuery = true)
    int resolveBookingIssue(@org.springframework.data.repository.query.Param("bookingId") UUID bookingId,
                            @org.springframework.data.repository.query.Param("reason") String reason,
                            @org.springframework.data.repository.query.Param("now") java.time.Instant now);
}
