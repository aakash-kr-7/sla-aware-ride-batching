package com.ridebatching.booking;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.batching.BatchPlanner;
import com.ridebatching.batching.BatchingService;
import com.ridebatching.booking.dto.BookingRequest;
import com.ridebatching.booking.dto.GeoPointDto;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.dispatch.*;
import com.ridebatching.driver.*;
import com.ridebatching.trip.*;
import com.ridebatching.zone.ServiceableZone;
import com.ridebatching.zone.ServiceableZoneRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingMutationRegressionIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired BookingRepository bookings;
    @Autowired EmployeeRepository employees;
    @Autowired BookingService service;
    @Autowired TripRepository trips;
    @Autowired ServiceableZoneRepository zones;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @Autowired DriverRepository drivers;
    @Autowired DriverOfferRepository offers;
    @Autowired DispatchService dispatch;
    @SpyBean BatchingService batching;
    @SpyBean BatchPlanner planner;
    @SpyBean DeduplicationKeyGenerator keys;

    @Test
    void cancellingLastRiderReleasesOnlyTheirAssignedDriver() {
        Booking booking = booking(employee(), 3600);
        Trip trip = trip(booking);
        Driver driver = availableDriver();
        Driver unrelated = availableDriver();
        Trip other = trip(booking(employee(), 3600));
        dispatch.acceptOffer(offers.saveAndFlush(new DriverOffer(UUID.randomUUID(), driver.getId(), trip.getId())).getId());
        dispatch.acceptOffer(offers.saveAndFlush(new DriverOffer(UUID.randomUUID(), unrelated.getId(), other.getId())).getId());
        service.cancelBooking(booking.getEmployee().getId(), booking.getId());
        assertThat(drivers.findById(driver.getId()).orElseThrow().getActiveTripId()).isNull();
        assertThat(trips.findById(trip.getId()).orElseThrow().getStatus()).isEqualTo(TripStatus.CANCELLED);
        assertThat(drivers.findById(unrelated.getId()).orElseThrow().getActiveTripId()).isEqualTo(other.getId());
        assertThat(trips.findById(other.getId()).orElseThrow().getStatus()).isEqualTo(TripStatus.DISPATCHED);
    }

    @Test
    void cancellingLastRiderExpiresOutstandingOffers() {
        Booking booking = booking(employee(), 3600);
        Trip trip = trip(booking);
        DriverOffer offer = offers.saveAndFlush(new DriverOffer(UUID.randomUUID(), availableDriver().getId(), trip.getId()));
        service.cancelBooking(booking.getEmployee().getId(), booking.getId());
        assertThat(offers.findById(offer.getId()).orElseThrow().getStatus()).isEqualTo(DriverOfferStatus.EXPIRED);
        assertThatThrownBy(() -> dispatch.acceptOffer(offer.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pickedUpRiderCannotBeRemovedByCancellation() {
        Booking booking = booking(employee(), 3600);
        Trip trip = trip(booking);
        jdbc.update("UPDATE bookings SET status='PICKED_UP' WHERE id=?", booking.getId());
        assertThatThrownBy(() -> service.cancelBooking(booking.getEmployee().getId(), booking.getId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PICKED_UP);
        assertThat(stopCount(booking.getId())).isEqualTo(2);
        assertThat(trips.findById(trip.getId()).orElseThrow().getStatus()).isEqualTo(TripStatus.PLANNED);
    }

    private Driver availableDriver() {
        Driver driver = new Driver(UUID.randomUUID(), "review", 4);
        driver.goOnline();
        return drivers.saveAndFlush(driver);
    }

    @Test
    void insertionFailureMustRollBackNewBooking() {
        Employee employee = employee();
        zones.save(new ServiceableZone("test", 0, 0, 1000, true));
        doThrow(new IllegalStateException("injected insertion failure"))
                .when(batching).attemptLateInsertion(any());
        assertThatThrownBy(() -> service.createBooking(employee.getId(), request()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bookings.count()).isZero();
    }

    @Test
    void duplicateAfterRepeatedCancelAndRebookMustReturnActiveBooking() {
        Employee employee = employee();
        zones.save(new ServiceableZone("test", 0, 0, 1000, true));
        // Stable identity isolates partial-index/history semantics from the wall clock.
        doReturn("history-key").when(keys).generateKey(any(), any(), any(), any());
        for (int i = 0; i < 2; i++) {
            var previous = service.createBooking(employee.getId(), request());
            service.cancelBooking(employee.getId(), previous.id());
        }
        var active = service.createBooking(employee.getId(), request());
        assertThat(service.createBooking(employee.getId(), request()).id()).isEqualTo(active.id());
        assertThat(bookings.count()).isEqualTo(3);
    }

    @Test
    void detachedAlreadyAssignedBookingMustNotEnterAnotherTrip() {
        Booking stale = booking(employee(), 3600);
        Trip first = trip(stale);
        trip(booking(employee(), 3600));
        assertThat(batching.attemptLateInsertion(stale)).isFalse();
        assertThat(bookings.findById(stale.getId()).orElseThrow().getTripId()).isEqualTo(first.getId());
        assertThat(stopCount(stale.getId())).isEqualTo(2);
    }

    @Test
    void detachedCancelledBookingMustNotBeResurrected() {
        trip(booking(employee(), 3600));
        Booking stale = booking(employee(), 3600);
        service.cancelBooking(stale.getEmployee().getId(), stale.getId());
        assertThat(batching.attemptLateInsertion(stale)).isFalse();
        assertThat(bookings.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(stopCount(stale.getId())).isZero();
    }

    @Test
    void concurrentCancellationMustNotBeOverwrittenByLateInsertion() throws Exception {
        trip(booking(employee(), 3600));
        Booking late = booking(employee(), 3600);
        CountDownLatch planning = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Booking candidate = invocation.getArgument(1);
            if (candidate.getId().equals(late.getId()) && first.getAndSet(false)) {
                planning.countDown();
                assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(planner).findBestTripForInsertion(anyList(), any(), anyMap(), any(), any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> insertion = executor.submit(() -> batching.attemptLateInsertion(late));
            assertThat(planning.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> cancellation = executor.submit(() -> transaction.execute(status -> {
                jdbc.execute("SET LOCAL application_name = 'cancel-review'");
                return service.cancelBooking(late.getEmployee().getId(), late.getId());
            }));
            // Old code commits cancellation; fixed code blocks on the claimed booking row.
            await(() -> cancellation.isDone() || blocked("cancel-review"));
            resume.countDown();
            insertion.get(10, TimeUnit.SECONDS);
            cancellation.get(10, TimeUnit.SECONDS);
            assertThat(bookings.findById(late.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(stopCount(late.getId())).isZero();
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void postLockEvaluationMustReloadNewRiderAndCheckTheirDeadline() throws Exception {
        Booking original = booking(employee(), 3600);
        Trip existing = trip(original);
        Booking late = booking(employee(), 3600);
        Booking added = booking(employee(), 180);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = transaction.execute(status -> {
                Trip locked = trips.findByIdForUpdate(existing.getId()).orElseThrow();
                Future<Boolean> insertion = executor.submit(() -> transaction.execute(inner -> {
                    jdbc.execute("SET LOCAL application_name = 'insertion-review'");
                    return batching.attemptLateInsertion(late);
                }));
                await(() -> blocked("insertion-review"));
                // Existing rider's pickup is at +2 minutes, before their +3 minute deadline.
                locked.assignRoute(List.of(stop(original, TripStopType.PICKUP), stop(added, TripStopType.PICKUP),
                        stop(original, TripStopType.DROPOFF), stop(added, TripStopType.DROPOFF)));
                jdbc.update("UPDATE bookings SET status='CONFIRMED', trip_id=? WHERE id=?", existing.getId(), added.getId());
                return insertion;
            });
            // Cheapest extra insertion delays added rider to +6 minutes. Must reject, not use the stale map.
            assertThat(result.get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(bookings.findById(late.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING);
            assertThat(stopCount(late.getId())).isZero();
            assertThat(stopCount(added.getId())).isEqualTo(2);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Employee employee() {
        UUID id = UUID.randomUUID();
        return employees.saveAndFlush(new Employee(id, id.toString(), "review", id + "@example.invalid"));
    }
    private Booking booking(Employee employee, long deadlineSeconds) {
        Instant now = Instant.now();
        return bookings.saveAndFlush(new Booking(UUID.randomUUID(), employee, new GeoPoint(0, 0), new GeoPoint(0, 0),
                now, BookingStatus.PENDING, now.plusSeconds(deadlineSeconds), now, UUID.randomUUID().toString()));
    }
    private Trip trip(Booking booking) {
        Trip trip = new Trip(UUID.randomUUID(), Instant.now(), Instant.now());
        trip.assignRoute(List.of(stop(booking, TripStopType.PICKUP), stop(booking, TripStopType.DROPOFF)));
        Trip saved = trips.saveAndFlush(trip);
        jdbc.update("UPDATE bookings SET status='CONFIRMED', trip_id=? WHERE id=?", saved.getId(), booking.getId());
        return saved;
    }
    private TripStop stop(Booking booking, TripStopType type) {
        return new TripStop(UUID.randomUUID(), booking.getId(), type,
                type == TripStopType.PICKUP ? booking.getPickupLocation() : booking.getDropoffLocation());
    }
    private BookingRequest request() { return new BookingRequest(new GeoPointDto(0.0, 0.0), new GeoPointDto(0.0, 0.0)); }
    private int stopCount(UUID id) { return jdbc.queryForObject("SELECT count(*) FROM trip_stops WHERE booking_id=?", Integer.class, id); }
    private boolean blocked(String name) {
        // PostgreSQL caches statistics snapshots within a transaction; refresh each observation.
        jdbc.execute("SELECT pg_stat_clear_snapshot()");
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock')", Boolean.class, name));
    }
    private void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertThat(condition.getAsBoolean()).as("actual DB lock wait or completed competing operation").isTrue();
    }
}
