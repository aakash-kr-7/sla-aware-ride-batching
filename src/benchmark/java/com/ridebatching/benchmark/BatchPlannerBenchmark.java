package com.ridebatching.benchmark;

import com.ridebatching.batching.BatchPlanner;
import com.ridebatching.batching.constraint.CapacityConstraint;
import com.ridebatching.batching.constraint.DetourConstraint;
import com.ridebatching.batching.constraint.PickupSlaConstraint;
import com.ridebatching.batching.evaluator.TripFeasibilityEvaluator;
import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.booking.Employee;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.geo.TravelTimeEstimator;
import com.ridebatching.geo.LocalTravelTimeEstimator;
import com.ridebatching.trip.RouteInsertionPlanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.openjdk.jmh.annotations.*;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
public class BatchPlannerBenchmark {

    @Param({"10", "25", "50", "100", "250"})
    private int clusterSize;

    private BatchPlanner planner;
    private List<Booking> bookings;
    private Instant routeStartTime;

    @Setup(Level.Trial)
    public void setup() {
        TravelTimeEstimator travelTimeEstimator = new LocalTravelTimeEstimator(1.35, 30.0, 120L);
        RouteInsertionPlanner routeInsertionPlanner = new RouteInsertionPlanner(travelTimeEstimator);

        CapacityConstraint capacityConstraint = new CapacityConstraint();
        PickupSlaConstraint pickupSlaConstraint = new PickupSlaConstraint(travelTimeEstimator);
        DetourConstraint detourConstraint = new DetourConstraint(travelTimeEstimator, 0.4, Duration.ofMinutes(15));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        TripFeasibilityEvaluator evaluator = new TripFeasibilityEvaluator(
                capacityConstraint, pickupSlaConstraint, detourConstraint, meterRegistry);

        planner = new BatchPlanner(routeInsertionPlanner, evaluator, travelTimeEstimator, 4, meterRegistry);

        bookings = generateDeterministicBookings(clusterSize);
        routeStartTime = Instant.parse("2026-10-10T12:00:00Z");
    }

    @Benchmark
    public void benchmarkBatchPlanning() {
        planner.planBatch(bookings, routeStartTime);
    }

    private List<Booking> generateDeterministicBookings(int count) {
        List<Booking> list = new ArrayList<>();
        Instant baseTime = Instant.parse("2026-10-10T11:50:00Z");

        // Deterministic sequence of locations to create a mix of feasible and infeasible combinations
        double[][] points = {
                {40.7128, -74.0060},
                {40.7138, -74.0050},
                {40.7148, -74.0040},
                {40.7580, -73.9855},
                {40.7590, -73.9845}
        };

        for (int i = 0; i < count; i++) {
            UUID id = new UUID(0, i);
            Employee employee = new Employee(new UUID(0, i % 10), "CORP-" + i, "Emp " + i, "emp" + i + "@example.com");
            double[] pu = points[i % points.length];
            double[] doLoc = points[(i + 2) % points.length];

            GeoPoint pickup = new GeoPoint(pu[0], pu[1]);
            GeoPoint dropoff = new GeoPoint(doLoc[0], doLoc[1]);

            Instant createdAt = baseTime.plus(i, ChronoUnit.SECONDS);
            Instant pickupSlaDeadline = createdAt.plus(20, ChronoUnit.MINUTES);
            
            Booking b = new Booking(
                    id,
                    employee,
                    pickup,
                    dropoff,
                    baseTime,
                    BookingStatus.PENDING,
                    pickupSlaDeadline,
                    createdAt,
                    "dedup-" + i
            );
            
            list.add(b);
        }
        return list;
    }
}
