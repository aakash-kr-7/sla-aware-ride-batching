package com.ridebatching.booking;

import com.ridebatching.geo.GeoPoint;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Encapsulates the deterministic deduplication key strategy defined in D03 and D04.
 * <p>
 * Generates a consistent, persistent string key (avoiding transient Object.hashCode())
 * based on the employee, the batching slot, and the normalized coordinates.
 */
@Component
public class DeduplicationKeyGenerator {

    /**
     * Number of decimal places for coordinate normalization.
     * 4 decimal places roughly equates to 11 meters resolution.
     */
    private static final int COORDINATE_PRECISION = 4;

    public String generateKey(UUID employeeId, GeoPoint pickup, GeoPoint dropoff, Instant slotTimestamp) {
        return String.format(Locale.US, "%s|%.4f|%.4f|%.4f|%.4f|%s",
                employeeId.toString(),
                normalize(pickup.latitude()),
                normalize(pickup.longitude()),
                normalize(dropoff.latitude()),
                normalize(dropoff.longitude()),
                slotTimestamp.toString());
    }

    private double normalize(double coordinate) {
        return BigDecimal.valueOf(coordinate)
                .setScale(COORDINATE_PRECISION, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
