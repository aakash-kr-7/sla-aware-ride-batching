ALTER TABLE bookings ADD COLUMN deduplication_key VARCHAR(255);

DROP INDEX IF EXISTS idx_bookings_dedup;

CREATE UNIQUE INDEX idx_bookings_dedup ON bookings (deduplication_key) WHERE status != 'CANCELLED';
