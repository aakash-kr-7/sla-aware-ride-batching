package com.ridebatching.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ridebatching.AbstractPostgresIntegrationTest;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@AutoConfigureMockMvc
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class BookingRetrievalIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    private Employee employee;

    @BeforeEach
    void setUp() {
        bookingRepository.deleteAll();
        employeeRepository.deleteAll();

        employee = employeeRepository.saveAndFlush(new Employee(
            UUID.randomUUID(), "EMP-RETRIEVAL-1", "Alice", "alice@example.com"
        ));
    }

    @Test
    void shouldRetrieveBookingById_Found() throws Exception {
        Booking booking = createAndSaveBooking(employee, "dedup1");

        mockMvc.perform(get("/api/v1/bookings/{id}", booking.getId())
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(booking.getId().toString()))
            .andExpect(jsonPath("$.employeeId").value(employee.getId().toString()))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void shouldReturnNotFound_WhenBookingDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/{id}", UUID.randomUUID())
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
    }

    @Test
    void shouldListBookingsForEmployee_WithPagination() throws Exception {
        createAndSaveBooking(employee, "dedup1");
        createAndSaveBooking(employee, "dedup2");
        createAndSaveBooking(employee, "dedup3");

        mockMvc.perform(get("/api/v1/bookings")
                .header("X-Employee-Id", employee.getId())
                .param("page", "0")
                .param("size", "2")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(2)))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/v1/bookings")
                .header("X-Employee-Id", employee.getId())
                .param("page", "1")
                .param("size", "2")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void shouldReturnEmptyResult_WhenEmployeeHasNoBookings() throws Exception {
        mockMvc.perform(get("/api/v1/bookings")
                .header("X-Employee-Id", employee.getId())
                .param("page", "0")
                .param("size", "10")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(0)))
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    private Booking createAndSaveBooking(Employee emp, String dedupKey) {
        Booking booking = new Booking(
            UUID.randomUUID(),
            emp,
            new com.ridebatching.geo.GeoPoint(12.9716, 77.5946),
            new com.ridebatching.geo.GeoPoint(12.9100, 77.6000),
            Instant.now(),
            BookingStatus.PENDING,
            Instant.now().plusSeconds(1200),
            Instant.now(),
            dedupKey
        );
        return bookingRepository.saveAndFlush(booking);
    }
}
