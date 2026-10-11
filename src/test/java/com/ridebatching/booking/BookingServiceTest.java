package com.ridebatching.booking;

import com.ridebatching.booking.dto.BookingRequest;
import com.ridebatching.booking.dto.BookingResponse;
import com.ridebatching.booking.dto.GeoPointDto;
import com.ridebatching.booking.exception.UnserviceableZoneException;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.zone.ServiceabilityService;
import com.ridebatching.zone.ServiceableZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private ServiceabilityService serviceabilityService;

    @Mock
    private DeduplicationKeyGenerator deduplicationKeyGenerator;

    @Mock
    private com.ridebatching.trip.TripRepository tripRepository;

    @Mock
    private com.ridebatching.config.AppProperties appProperties;

    @Mock
    private com.ridebatching.batching.BatchingService batchingService;
    @Mock
    private com.ridebatching.dispatch.DispatchService dispatchService;
    @Mock private com.ridebatching.trip.TripRouteTiming routeTiming;
    @Mock private com.ridebatching.escalation.BookingSlaEscalations escalations;

    @org.mockito.Spy
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    @InjectMocks
    private BookingService bookingService;

    @Test
    void shouldCreateBookingWhenZoneIsServiceable() {
        UUID employeeId = UUID.randomUUID();
        Employee employee = new Employee(employeeId, "EMP-100", "Alice", "alice@example.com");
        
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));
        when(serviceabilityService.findZoneFor(any(GeoPoint.class)))
            .thenReturn(Optional.of(new ServiceableZone("HQ", 12.9716, 77.5946, 5000, true)));
        when(deduplicationKeyGenerator.generateKey(any(), any(), any(), any()))
            .thenReturn("dummy-key");
        
        com.ridebatching.config.AppProperties.BatchingProperties batchingProps = 
            new com.ridebatching.config.AppProperties.BatchingProperties(15, 45);
        when(appProperties.batching()).thenReturn(batchingProps);
        when(appProperties.sla()).thenReturn(new com.ridebatching.config.AppProperties.SlaProperties(25));

        when(bookingRepository.insertIfAbsent(any(Booking.class))).thenAnswer(i -> {
            Booking inserted = i.getArgument(0);
            when(bookingRepository.findById(inserted.getId())).thenReturn(Optional.of(inserted));
            return 1;
        });

        BookingRequest request = new BookingRequest(
            new GeoPointDto(12.9716, 77.5946),
            new GeoPointDto(12.9352, 77.6245)
        );

        BookingResponse response = bookingService.createBooking(employeeId, request);

        assertNotNull(response.id());
        assertEquals(BookingStatus.PENDING, response.status());
        assertNull(response.tripId());
        assertEquals(employeeId, response.employeeId());
        assertEquals(java.time.Duration.ofMinutes(25),
                java.time.Duration.between(response.createdAt(), response.pickupSlaDeadline()));
    }

    @Test
    void shouldThrowExceptionWhenZoneUnserviceable() {
        UUID employeeId = UUID.randomUUID();
        
        when(serviceabilityService.findZoneFor(any(GeoPoint.class))).thenReturn(Optional.empty());

        BookingRequest request = new BookingRequest(
            new GeoPointDto(12.9716, 77.5946),
            new GeoPointDto(12.9352, 77.6245)
        );

        assertThrows(UnserviceableZoneException.class, () -> bookingService.createBooking(employeeId, request));
    }

    @Test
    void shouldCancelPendingBookingSuccessfully() {
        UUID employeeId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Employee employee = new Employee(employeeId, "EMP-100", "Alice", "alice@example.com");
        
        Booking booking = new Booking(bookingId, employee, new GeoPoint(0,0), new GeoPoint(0,0), 
            java.time.Instant.now(), BookingStatus.PENDING, java.time.Instant.now(), java.time.Instant.now(), "key");
            
        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArguments()[0]);

        BookingResponse response = bookingService.cancelBooking(employeeId, bookingId);

        assertEquals(BookingStatus.CANCELLED, response.status());
        verify(bookingRepository).save(booking);
    }

    @Test
    void shouldRemoveBookingFromTrip() {
        UUID employeeId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        Employee employee = new Employee(employeeId, "EMP-100", "Alice", "alice@example.com");
        
        Booking booking = new Booking(bookingId, employee, new GeoPoint(0,0), new GeoPoint(0,0), 
            java.time.Instant.now(), BookingStatus.CONFIRMED, java.time.Instant.now(), java.time.Instant.now(), "key");
        booking.setTripId(tripId);
            
        com.ridebatching.trip.Trip trip = mock(com.ridebatching.trip.Trip.class);
        
        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(booking));
        when(tripRepository.findByIdForUpdate(tripId)).thenReturn(Optional.of(trip));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArguments()[0]);

        BookingResponse response = bookingService.cancelBooking(employeeId, bookingId);

        assertEquals(BookingStatus.CANCELLED, response.status());
        verify(trip).removeBooking(bookingId);
        verify(tripRepository).save(trip);
        verify(bookingRepository).save(booking);
    }

    @Test
    void shouldThrowExceptionWhenCancellingDroppedBooking() {
        UUID employeeId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Employee employee = new Employee(employeeId, "EMP-100", "Alice", "alice@example.com");
        
        Booking booking = new Booking(bookingId, employee, new GeoPoint(0,0), new GeoPoint(0,0), 
            java.time.Instant.now(), BookingStatus.DROPPED, java.time.Instant.now(), java.time.Instant.now(), "key");
            
        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(booking));

        IllegalStateException exception = assertThrows(IllegalStateException.class, 
            () -> bookingService.cancelBooking(employeeId, bookingId));
        assertEquals("Booking cannot be cancelled from status: DROPPED", exception.getMessage());
    }
}
