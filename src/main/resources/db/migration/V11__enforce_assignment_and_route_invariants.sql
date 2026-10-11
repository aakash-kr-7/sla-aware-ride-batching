-- Fail closed if legacy rows violate these invariants. Repair existing data before upgrading;
-- do not invent dedup identities or silently discard assignment history.
ALTER TABLE bookings ALTER COLUMN deduplication_key SET NOT NULL;

ALTER TABLE driver_offers ADD CONSTRAINT fk_driver_offers_driver FOREIGN KEY (driver_id) REFERENCES drivers(id);
ALTER TABLE driver_offers ADD CONSTRAINT fk_driver_offers_trip FOREIGN KEY (trip_id) REFERENCES trips(id);
ALTER TABLE trips ADD CONSTRAINT fk_trips_driver FOREIGN KEY (driver_id) REFERENCES drivers(id);
ALTER TABLE drivers ADD CONSTRAINT fk_drivers_active_trip FOREIGN KEY (active_trip_id) REFERENCES trips(id);
ALTER TABLE drivers ADD CONSTRAINT chk_driver_capacity CHECK (vehicle_capacity > 0);

-- Route replacements/resequencing can temporarily overlap old/new rows during a JPA flush.
-- Enforce the final transaction state rather than Hibernate's statement order.
ALTER TABLE trip_stops ADD CONSTRAINT uq_booking_stop_type UNIQUE (booking_id, type) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE trip_stops ADD CONSTRAINT uq_trip_stop_sequence UNIQUE (trip_id, sequence_order) DEFERRABLE INITIALLY DEFERRED;

-- A stale detached location/status update must not overwrite a newer assignment or transition.
ALTER TABLE drivers ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
