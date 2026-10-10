CREATE TABLE drivers (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    active_trip_id UUID,
    vehicle_capacity INT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_drivers_status ON drivers(status);
CREATE INDEX idx_drivers_active_trip ON drivers(active_trip_id);
