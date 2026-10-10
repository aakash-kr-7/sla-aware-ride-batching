package com.ridebatching.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.booking.dto.BookingRequest;
import com.ridebatching.booking.dto.GeoPointDto;
import com.ridebatching.zone.ServiceableZone;
import com.ridebatching.zone.ServiceableZoneRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

@AutoConfigureMockMvc
class BookingCreationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private ServiceableZoneRepository zoneRepository;

    @Test
    void shouldPersistBookingFromApi() throws Exception {
        // Setup initial data
        Employee employee = new Employee(UUID.randomUUID(), "EMP-INTEG-1", "Bob", "bob.integ@example.com");
        employeeRepository.saveAndFlush(employee);

        ServiceableZone zone = new ServiceableZone("Tech Park", 12.0, 77.0, 5000, true);
        zoneRepository.saveAndFlush(zone);

        BookingRequest request = new BookingRequest(
            new GeoPointDto(12.001, 77.001), // Inside zone
            new GeoPointDto(12.9352, 77.6245)
        );

        mockMvc.perform(post("/api/v1/bookings")
                .header("X-Employee-Id", employee.getId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.status").value("PENDING"));

        assertEquals(1, bookingRepository.count());
    }
}
