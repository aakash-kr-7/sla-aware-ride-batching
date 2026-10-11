package com.ridebatching.batching;

import com.ridebatching.batching.constraint.CapacityConstraint;
import com.ridebatching.batching.constraint.DetourConstraint;
import com.ridebatching.batching.constraint.PickupSlaConstraint;
import com.ridebatching.batching.evaluator.TripFeasibilityEvaluator;
import com.ridebatching.geo.TravelTimeEstimator;
import com.ridebatching.trip.RouteInsertionPlanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
public class BatchingConfiguration {

    @Bean
    public java.time.Clock batchingClock() {
        return java.time.Clock.systemUTC();
    }

    @Bean
    public CapacityConstraint capacityConstraint() {
        return new CapacityConstraint();
    }

    @Bean
    public PickupSlaConstraint pickupSlaConstraint(TravelTimeEstimator travelTimeEstimator) {
        return new PickupSlaConstraint(travelTimeEstimator);
    }

    @Bean
    public DetourConstraint detourConstraint(TravelTimeEstimator travelTimeEstimator,
                                             @Value("${app.batching.max-relative-detour-ratio:1.0}") double maxRelativeDetourRatio,
                                             @Value("${app.batching.max-absolute-detour-minutes:30}") long maxAbsoluteDetourMinutes) {
        return new DetourConstraint(travelTimeEstimator, maxRelativeDetourRatio, Duration.ofMinutes(maxAbsoluteDetourMinutes));
    }

    @Bean
    public TripFeasibilityEvaluator tripFeasibilityEvaluator(CapacityConstraint capacityConstraint,
                                                             PickupSlaConstraint pickupSlaConstraint,
                                                             DetourConstraint detourConstraint,
                                                             MeterRegistry meterRegistry) {
        return new TripFeasibilityEvaluator(capacityConstraint, pickupSlaConstraint, detourConstraint, meterRegistry);
    }

    @Bean
    public BatchPlanner batchPlanner(RouteInsertionPlanner routeInsertionPlanner,
                                     TripFeasibilityEvaluator tripFeasibilityEvaluator,
                                     TravelTimeEstimator travelTimeEstimator,
                                     @Value("${app.fleet.default-vehicle-capacity:4}") int defaultVehicleCapacity,
                                     MeterRegistry meterRegistry) {
        return new BatchPlanner(routeInsertionPlanner, tripFeasibilityEvaluator, travelTimeEstimator, defaultVehicleCapacity, meterRegistry);
    }
}
