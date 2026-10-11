-- Keep history; reject inconsistent legacy duplicates rather than silently deleting them.
CREATE UNIQUE INDEX idx_open_sla_issue
    ON sla_escalation (reference_type, reference_id, reason) WHERE status = 'OPEN';
