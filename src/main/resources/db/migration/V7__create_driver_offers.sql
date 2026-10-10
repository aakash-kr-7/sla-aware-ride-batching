CREATE TABLE driver_offers (
    id UUID PRIMARY KEY,
    driver_id UUID NOT NULL,
    trip_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Quick lookups for a trip's offers
CREATE INDEX idx_driver_offers_trip ON driver_offers(trip_id);

-- Lookup to see if a driver was recently offered/rejected a trip
CREATE INDEX idx_driver_offers_driver_trip ON driver_offers(driver_id, trip_id);

-- One trip must not be ultimately accepted by multiple drivers
CREATE UNIQUE INDEX idx_accepted_trip_offer ON driver_offers (trip_id) WHERE status = 'ACCEPTED';
