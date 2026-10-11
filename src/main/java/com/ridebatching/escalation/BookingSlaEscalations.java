package com.ridebatching.escalation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/** Stable reasons identify unresolved issues; changing ETA values must not create new identities. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class BookingSlaEscalations {
    public static final String SOLO_UNREACHABLE = "Pickup deadline cannot be met even by a solo trip";
    public static final String ROUTE_AT_RISK = "Replanned pickup estimate exceeds the booking deadline";
    private final SlaEscalationRepository repository;
    public BookingSlaEscalations(SlaEscalationRepository repository) { this.repository = repository; }

    public void soloUnreachable(UUID bookingId, Instant now) {
        repository.openBookingIssue(UUID.randomUUID(), bookingId, SOLO_UNREACHABLE, now);
    }
    public void routeRisk(UUID bookingId, boolean late, Instant now) {
        if (late) repository.openBookingIssue(UUID.randomUUID(), bookingId, ROUTE_AT_RISK, now);
        else repository.resolveBookingIssue(bookingId, ROUTE_AT_RISK, now);
    }
    public void resolve(UUID bookingId, Instant now) {
        repository.resolveBookingIssue(bookingId, SOLO_UNREACHABLE, now);
        repository.resolveBookingIssue(bookingId, ROUTE_AT_RISK, now);
    }
}
