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
import static org.mockito.Mockito.when;

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
    private com.ridebatching.config.AppProperties appProperties;

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

        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(i -> i.getArguments()[0]);

        BookingRequest request = new BookingRequest(
            new GeoPointDto(12.9716, 77.5946),
            new GeoPointDto(12.9352, 77.6245)
        );

        BookingResponse response = bookingService.createBooking(employeeId, request);

        assertNotNull(response.id());
        assertEquals(BookingStatus.PENDING, response.status());
        assertNull(response.tripId());
        assertEquals(employeeId, response.employeeId());
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
}
