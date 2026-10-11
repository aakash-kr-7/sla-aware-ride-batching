ALTER TABLE driver_offers ADD COLUMN expires_at TIMESTAMPTZ;
-- Existing offers receive the same two-minute lifetime as the default policy.
UPDATE driver_offers SET expires_at = created_at + interval '2 minutes';
ALTER TABLE driver_offers ALTER COLUMN expires_at SET NOT NULL;
CREATE INDEX idx_driver_offers_due ON driver_offers (expires_at, trip_id)
    WHERE status = 'OFFERED';
