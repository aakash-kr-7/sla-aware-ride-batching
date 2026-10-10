CREATE TABLE employees (
    id UUID PRIMARY KEY,
    corporate_id VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE bookings (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employees(id),
    trip_id UUID,
    pickup_latitude DOUBLE PRECISION NOT NULL,
    pickup_longitude DOUBLE PRECISION NOT NULL,
    dropoff_latitude DOUBLE PRECISION NOT NULL,
    dropoff_longitude DOUBLE PRECISION NOT NULL,
    slot_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(50) NOT NULL,
    pickup_sla_deadline TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    
    CONSTRAINT chk_booking_pickup_lat CHECK (pickup_latitude >= -90.0 AND pickup_latitude <= 90.0),
    CONSTRAINT chk_booking_pickup_lon CHECK (pickup_longitude >= -180.0 AND pickup_longitude <= 180.0),
    CONSTRAINT chk_booking_dropoff_lat CHECK (dropoff_latitude >= -90.0 AND dropoff_latitude <= 90.0),
    CONSTRAINT chk_booking_dropoff_lon CHECK (dropoff_longitude >= -180.0 AND dropoff_longitude <= 180.0)
);

CREATE INDEX idx_bookings_employee_id ON bookings(employee_id);
CREATE INDEX idx_bookings_status ON bookings(status);
CREATE INDEX idx_bookings_slot_timestamp ON bookings(slot_timestamp);

-- Unique index enforcing single-booking idempotency (H2) and coordinate normalization (D04)
CREATE UNIQUE INDEX idx_bookings_dedup 
ON bookings (
    employee_id, 
    ROUND(CAST(pickup_latitude AS NUMERIC), 4),
    ROUND(CAST(pickup_longitude AS NUMERIC), 4),
    ROUND(CAST(dropoff_latitude AS NUMERIC), 4),
    ROUND(CAST(dropoff_longitude AS NUMERIC), 4),
    slot_timestamp
) 
WHERE status != 'CANCELLED';
