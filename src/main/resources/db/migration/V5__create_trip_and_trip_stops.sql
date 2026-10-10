CREATE TABLE trips (
    id UUID PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    vehicle_id UUID,
    driver_id UUID,
    window_start_time TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE trip_stops (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trips(id),
    booking_id UUID NOT NULL REFERENCES bookings(id),
    type VARCHAR(50) NOT NULL,
    sequence_order INT NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    
    CONSTRAINT chk_trip_stop_lat CHECK (latitude >= -90.0 AND latitude <= 90.0),
    CONSTRAINT chk_trip_stop_lon CHECK (longitude >= -180.0 AND longitude <= 180.0)
);

CREATE INDEX idx_trip_stops_trip_id ON trip_stops(trip_id);
CREATE INDEX idx_trip_stops_booking_id ON trip_stops(booking_id);

ALTER TABLE bookings ADD CONSTRAINT fk_bookings_trip_id FOREIGN KEY (trip_id) REFERENCES trips(id);
