CREATE TABLE serviceable_zones (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    center_latitude DOUBLE PRECISION NOT NULL,
    center_longitude DOUBLE PRECISION NOT NULL,
    radius_meters DOUBLE PRECISION NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    
    CONSTRAINT chk_zone_lat CHECK (center_latitude >= -90.0 AND center_latitude <= 90.0),
    CONSTRAINT chk_zone_lon CHECK (center_longitude >= -180.0 AND center_longitude <= 180.0),
    CONSTRAINT chk_zone_radius CHECK (radius_meters > 0)
);

CREATE INDEX idx_serviceable_zones_active ON serviceable_zones(active);
