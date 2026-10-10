-- V10__add_performance_indexes.sql

-- 1. Index pending bookings for BatchingService
-- The batch planner continuously queries for PENDING bookings. A partial index ensures 
-- we don't index the vast majority of historical (completed/cancelled) bookings.
CREATE INDEX idx_bookings_status_pending ON bookings (status) WHERE status = 'PENDING';

-- 2. Index employee bookings
-- Required for fetching booking history by employee.
CREATE INDEX idx_bookings_employee_id ON bookings (employee_id);

-- 3. Index trips by status
-- Required for finding 'PLANNED' trips during late insertion evaluation.
CREATE INDEX idx_trips_status ON trips (status);

-- 4. Index driver offers by trip
-- Dispatch service needs to quickly load all offers for a specific trip.
CREATE INDEX idx_driver_offers_trip_id ON driver_offers (trip_id);

-- 5. Index driver offers by driver and trip for cool-off lookup
-- DriverEligibility and CoolOff policies evaluate specific driver+trip combinations.
CREATE INDEX idx_driver_offers_driver_trip_status ON driver_offers (driver_id, trip_id, status);

-- 6. Index open escalations
-- Similar to pending bookings, we only care about actively OPEN escalations.
CREATE INDEX idx_sla_escalation_status_open ON sla_escalation (status) WHERE status = 'OPEN';
