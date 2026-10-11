-- Deadline ordering skips expired requests and supports bounded, earliest-deadline claims.
-- The row limit bounds planner input, not the database's total scan cost.
CREATE INDEX idx_bookings_batch_claim
    ON bookings (pickup_sla_deadline, created_at, id)
    WHERE status = 'PENDING' AND trip_id IS NULL;
