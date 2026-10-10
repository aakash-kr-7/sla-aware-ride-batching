package com.ridebatching.booking.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridebatching.booking.BookingService;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.booking.dto.BookingRequest;
import com.ridebatching.booking.dto.BookingResponse;
import com.ridebatching.booking.dto.GeoPointDto;
import com.ridebatching.booking.exception.UnserviceableZoneException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;

@WebMvcTest(controllers = BookingController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class})
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BookingService bookingService;

    @MockBean
    private com.ridebatching.auth.JwtService jwtService;

    @MockBean
    private com.ridebatching.auth.CustomUserDetailsService userDetailsService;

    @Test
    void shouldCreateBooking() throws Exception {
        UUID employeeId = UUID.randomUUID();
        BookingRequest request = new BookingRequest(
            new GeoPointDto(12.9716, 77.5946),
            new GeoPointDto(12.9352, 77.6245)
        );

        BookingResponse response = new BookingResponse(
            UUID.randomUUID(),
            employeeId,
            request.pickup(),
            request.dropoff(),
            Instant.parse("2026-10-10T12:00:00Z"),
            BookingStatus.PENDING,
            Instant.parse("2026-10-10T12:21:15Z"),
            null,
            Instant.parse("2026-10-10T12:01:15Z")
        );

        when(bookingService.createBooking(eq(employeeId), any(BookingRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/bookings")
                .header("X-Employee-Id", employeeId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(response.id().toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.tripId").isEmpty());
    }

    @Test
    void shouldRejectInvalidCoordinates() throws Exception {
        UUID employeeId = UUID.randomUUID();
        // Latitude > 90
        BookingRequest request = new BookingRequest(
            new GeoPointDto(91.0, 77.5946),
            new GeoPointDto(12.9352, 77.6245)
        );

        mockMvc.perform(post("/api/v1/bookings")
                .header("X-Employee-Id", employeeId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn422ForUnserviceableZone() throws Exception {
        UUID employeeId = UUID.randomUUID();
        BookingRequest request = new BookingRequest(
            new GeoPointDto(12.8500, 77.5000),
            new GeoPointDto(12.9352, 77.6245)
        );

        when(bookingService.createBooking(eq(employeeId), any(BookingRequest.class)))
            .thenThrow(new UnserviceableZoneException("Pickup location is outside all active serviceable zones."));

        mockMvc.perform(post("/api/v1/bookings")
                .header("X-Employee-Id", employeeId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("UNSERVICEABLE_ZONE"));
    }
}
