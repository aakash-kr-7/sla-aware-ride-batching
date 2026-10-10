package com.ridebatching.driver;

import com.ridebatching.geo.GeoPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DriverProximityRankerTest {

    private final DriverProximityRanker ranker = new DriverProximityRanker();

    @Test
    void ranksDriversByStraightLineDistance() {
        GeoPoint origin = new GeoPoint(12.9716, 77.5946);

        Driver driverNear = new Driver(UUID.randomUUID(), "Near", 4);
        driverNear.updateLocation(new GeoPoint(12.9720, 77.5950)); // Very close

        Driver driverFar = new Driver(UUID.randomUUID(), "Far", 4);
        driverFar.updateLocation(new GeoPoint(13.0, 78.0)); // Far away

        Driver driverMid = new Driver(UUID.randomUUID(), "Mid", 4);
        driverMid.updateLocation(new GeoPoint(12.9800, 77.6000)); // Medium distance

        List<Driver> ranked = ranker.rankByProximity(List.of(driverFar, driverNear, driverMid), origin);

        assertThat(ranked).extracting(Driver::getName)
                .containsExactly("Near", "Mid", "Far");
    }

    @Test
    void breaksTiesDeterministicallyById() {
        GeoPoint origin = new GeoPoint(12.9716, 77.5946);
        GeoPoint sameLocation = new GeoPoint(12.9720, 77.5950);

        UUID id1 = new UUID(0, 1);
        UUID id2 = new UUID(0, 2);
        UUID id3 = new UUID(0, 3);

        Driver d1 = new Driver(id1, "D1", 4);
        d1.updateLocation(sameLocation);

        Driver d2 = new Driver(id2, "D2", 4);
        d2.updateLocation(sameLocation);

        Driver d3 = new Driver(id3, "D3", 4);
        d3.updateLocation(sameLocation);

        // Mix the input order
        List<Driver> ranked = ranker.rankByProximity(List.of(d3, d1, d2), origin);

        assertThat(ranked).extracting(Driver::getId)
                .containsExactly(id1, id2, id3);
    }
}
