-- V10__add_performance_indexes.sql

-- 1. Index pending bookings for BatchingService
-- The batch planner continuously queries for PENDING bookings. A partial index ensures 
-- we don't index the vast majority of historical (completed/cancelled) bookings.
CREATE INDEX idx_bookings_status_pending ON bookings (status) WHERE status = 'PENDING';

-- Employee booking history is already indexed by V3.

-- 2. Index trips by status
-- Required for finding 'PLANNED' trips during late insertion evaluation.
CREATE INDEX idx_trips_status ON trips (status);

-- Driver offers by trip are already indexed by V7.

-- 3. Index driver offers by driver and trip for cool-off lookup
-- DriverEligibility and CoolOff policies evaluate specific driver+trip combinations.
CREATE INDEX idx_driver_offers_driver_trip_status ON driver_offers (driver_id, trip_id, status);

-- 4. Index open escalations
-- Similar to pending bookings, we only care about actively OPEN escalations.
CREATE INDEX idx_sla_escalation_status_open ON sla_escalation (status) WHERE status = 'OPEN';
