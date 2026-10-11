-- Older estimates cannot be reconstructed honestly; calculate them when a route is next mutated.
ALTER TABLE trip_stops ADD COLUMN estimated_arrival_at TIMESTAMPTZ;
