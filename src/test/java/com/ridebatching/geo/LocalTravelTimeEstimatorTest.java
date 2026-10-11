package com.ridebatching.geo;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTravelTimeEstimatorTest {

    @Test
    void invalidConfigurationCannotProduceNegativeOrNonFiniteTravelTimes() {
        for (double value : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LocalTravelTimeEstimator(value, 30, 120))
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LocalTravelTimeEstimator(1.35, value, 120))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LocalTravelTimeEstimator(1.35, 30, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approachEndsAtArrivalAndDoesNotChargeDestinationDwell() {
        var estimator = new LocalTravelTimeEstimator(1.35, 30, 120);
        var pickup = new GeoPoint(0, 0); var driver = new GeoPoint(.01, 0);
        assertThat(estimator.estimateApproachTime(pickup, pickup)).isEqualTo(Duration.ZERO);
        long driving = (long) (DistanceCalculator.calculateDistanceInMetres(driver, pickup) * 1.35 / (30 / 3.6));
        assertThat(estimator.estimateApproachTime(driver, pickup)).isEqualTo(Duration.ofSeconds(driving));
        assertThat(estimator.estimateTravelTime(driver, pickup)).isEqualTo(Duration.ofSeconds(driving + 120));
    }

    @Test
    void shouldEstimateTravelTimeWithDwellTime() {
        // 1.35 circuity, 30km/h (8.33 m/s), 120s dwell
        LocalTravelTimeEstimator estimator = new LocalTravelTimeEstimator(1.35, 30.0, 120);

        // A coordinate near Bangalore
        GeoPoint origin = new GeoPoint(12.9716, 77.5946);
        // Another coordinate exactly 1 degree longitude away
        // Distance is ~ 108 km. Let's use a smaller distance for test predictability.
        
        // Same point should result in 0 distance + dwell time
        Duration timeForZeroDistance = estimator.estimateTravelTime(origin, origin);
        assertThat(timeForZeroDistance).isEqualTo(Duration.ofSeconds(120));

        // Roughly 1km apart (0.009 degrees of latitude)
        GeoPoint destination = new GeoPoint(12.9806, 77.5946);
        
        double straightDistance = DistanceCalculator.calculateDistanceInMetres(origin, destination);
        
        Duration travelTime = estimator.estimateTravelTime(origin, destination);
        
        long expectedTravelSeconds = (long) ((straightDistance * 1.35) / (30.0 / 3.6));
        
        assertThat(travelTime).isEqualTo(Duration.ofSeconds(expectedTravelSeconds + 120));
    }
}
