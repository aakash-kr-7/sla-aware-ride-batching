package com.ridebatching.batching;

import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.booking.Employee;
import com.ridebatching.config.AppProperties;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.zone.ServiceabilityService;
import com.ridebatching.zone.ServiceableZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestClustererTest {

    @Mock
    private ServiceabilityService serviceabilityService;

    @Mock
    private AppProperties appProperties;

    private RequestClusterer clusterer;

    @BeforeEach
    void setUp() {
        AppProperties.BatchingProperties batchingProps = new AppProperties.BatchingProperties(15, 45);
        when(appProperties.batching()).thenReturn(batchingProps);
        clusterer = new RequestClusterer(serviceabilityService, appProperties);
    }

    @Test
    void shouldClusterCompatibleBookingsInSameZone() {
        ServiceableZone zone = new ServiceableZone("North Zone", 0.0, 0.0, 5000, true);
        ReflectionTestUtils.setField(zone, "id", 1L);
        when(serviceabilityService.findZoneFor(any(GeoPoint.class))).thenReturn(Optional.of(zone));

        // These points should yield roughly the same bearing (straight North)
        Booking b1 = createBooking(new GeoPoint(0.0, 0.0), new GeoPoint(1.0, 0.0), 10);
        Booking b2 = createBooking(new GeoPoint(0.0, 0.0), new GeoPoint(1.1, 0.0), 20);

        List<List<Booking>> clusters = clusterer.clusterBookings(List.of(b1, b2));

        assertEquals(1, clusters.size());
        assertEquals(2, clusters.get(0).size());
    }

    @Test
    void shouldSplitBookingsByDifferentZones() {
        ServiceableZone zone1 = new ServiceableZone("Zone 1", 0.0, 0.0, 5000, true);
        ReflectionTestUtils.setField(zone1, "id", 1L);
        ServiceableZone zone2 = new ServiceableZone("Zone 2", 10.0, 10.0, 5000, true);
        ReflectionTestUtils.setField(zone2, "id", 2L);

        Booking b1 = createBooking(new GeoPoint(0.0, 0.0), new GeoPoint(1.0, 0.0), 10); // North
        Booking b2 = createBooking(new GeoPoint(10.0, 10.0), new GeoPoint(11.0, 10.0), 20); // North

        // Stub findZoneFor to return zone1 for b1 and zone2 for b2
        when(serviceabilityService.findZoneFor(b1.getPickupLocation())).thenReturn(Optional.of(zone1));
        when(serviceabilityService.findZoneFor(b2.getPickupLocation())).thenReturn(Optional.of(zone2));

        List<List<Booking>> clusters = clusterer.clusterBookings(List.of(b1, b2));

        assertEquals(2, clusters.size(), "Should form separate clusters for different zones");
        assertEquals(1, clusters.get(0).size());
        assertEquals(1, clusters.get(1).size());
    }

    @Test
    void shouldSplitBookingsWithIncompatibleBearings() {
        ServiceableZone zone = new ServiceableZone("North Zone", 0.0, 0.0, 5000, true);
        ReflectionTestUtils.setField(zone, "id", 1L);
        when(serviceabilityService.findZoneFor(any(GeoPoint.class))).thenReturn(Optional.of(zone));

        Booking bNorth = createBooking(new GeoPoint(0.0, 0.0), new GeoPoint(1.0, 0.0), 10);
        Booking bEast = createBooking(new GeoPoint(0.0, 0.0), new GeoPoint(0.0, 1.0), 20);
        Booking bSouth = createBooking(new GeoPoint(0.0, 0.0), new GeoPoint(-1.0, 0.0), 30);

        List<List<Booking>> clusters = clusterer.clusterBookings(List.of(bNorth, bEast, bSouth));

        assertEquals(3, clusters.size(), "Each booking is 90+ degrees apart, must be split into 3 clusters");
    }

    @Test
    void shouldHandleAwkwardAngularBoundariesCorrectly() {
        ServiceableZone zone = new ServiceableZone("North Zone", 0.0, 0.0, 5000, true);
        ReflectionTestUtils.setField(zone, "id", 1L);
        when(serviceabilityService.findZoneFor(any(GeoPoint.class))).thenReturn(Optional.of(zone));

        // Bearing ~359 degrees (slightly West of North)
        Booking b359 = createBooking(new GeoPoint(0.0, 0.0), new GeoPoint(10.0, -0.1), 10);
        // Bearing ~1 degrees (slightly East of North)
        Booking b1 = createBooking(new GeoPoint(0.0, 0.0), new GeoPoint(10.0, 0.1), 20);

        List<List<Booking>> clusters = clusterer.clusterBookings(List.of(b359, b1));

        assertEquals(1, clusters.size(), "Bearings near 0/360 should correctly compute angular distance (<= 45deg)");
        assertEquals(2, clusters.get(0).size());
    }
    
    @Test
    void shouldBeDeterministicByCreationTime() {
        ServiceableZone zone = new ServiceableZone("North Zone", 0.0, 0.0, 5000, true);
        ReflectionTestUtils.setField(zone, "id", 1L);
        when(serviceabilityService.findZoneFor(any(GeoPoint.class))).thenReturn(Optional.of(zone));

        Booking b1 = createBooking(new GeoPoint(0.0, 0.0), new GeoPoint(1.0, 0.0), 50);
        Booking b2 = createBooking(new GeoPoint(0.0, 0.0), new GeoPoint(1.0, 0.0), 10);
        Booking b3 = createBooking(new GeoPoint(0.0, 0.0), new GeoPoint(1.0, 0.0), 30);

        List<List<Booking>> clusters = clusterer.clusterBookings(List.of(b1, b2, b3));

        assertEquals(1, clusters.size());
        List<Booking> cluster = clusters.get(0);
        assertEquals(b2.getId(), cluster.get(0).getId());
        assertEquals(b3.getId(), cluster.get(1).getId());
        assertEquals(b1.getId(), cluster.get(2).getId());
    }

    private Booking createBooking(GeoPoint pickup, GeoPoint drop, int createOffsetSeconds) {
        Employee emp = new Employee(UUID.randomUUID(), "EMP", "Name", "email@example.com");
        Instant now = ZonedDateTime.of(2026, 10, 10, 10, 0, 0, 0, ZoneOffset.UTC).toInstant();
        Instant createdAt = now.plusSeconds(createOffsetSeconds);
        return new Booking(
            UUID.randomUUID(),
            emp,
            pickup,
            drop,
            now, // slotTimestamp
            BookingStatus.PENDING,
            now.plusSeconds(1200),
            createdAt,
            UUID.randomUUID().toString()
        );
    }

    @Test
    void leavesUnknownZonesOutInsteadOfSharingASentinelCluster() {
        when(serviceabilityService.findZoneFor(any(GeoPoint.class))).thenReturn(Optional.empty());
        assertEquals(List.of(), clusterer.clusterBookings(List.of(
                createBooking(new GeoPoint(0, 0), new GeoPoint(1, 0), 10),
                createBooking(new GeoPoint(0, 0), new GeoPoint(1, 0), 20))));
    }
}
