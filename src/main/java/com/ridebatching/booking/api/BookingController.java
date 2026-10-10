package com.ridebatching.booking.api;

import com.ridebatching.booking.BookingService;
import com.ridebatching.booking.dto.BookingRequest;
import com.ridebatching.booking.dto.BookingResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Employee (Bookings)", description = "Endpoints for employees to create, list, and cancel their ride bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or #employeeId == authentication.principal.employeeId")
    public BookingResponse createBooking(
            @RequestHeader("X-Employee-Id") UUID employeeId,
            @Valid @RequestBody BookingRequest request) {
        return bookingService.createBooking(employeeId, request);
    }

    @GetMapping("/{id}")
    @PostAuthorize("hasRole('ADMIN') or returnObject.employeeId() == authentication.principal.employeeId")
    public BookingResponse getBooking(@PathVariable UUID id) {
        return bookingService.getBooking(id);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or #employeeId == authentication.principal.employeeId")
    public BookingResponse cancelBooking(
            @RequestHeader("X-Employee-Id") UUID employeeId,
            @PathVariable UUID id) {
        return bookingService.cancelBooking(employeeId, id);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or #employeeId == authentication.principal.employeeId")
    public com.ridebatching.common.dto.PageResponse<BookingResponse> listBookings(
            @RequestHeader("X-Employee-Id") UUID employeeId,
            Pageable pageable) {
        Page<BookingResponse> page = bookingService.listBookingsByEmployee(employeeId, pageable);
        return com.ridebatching.common.dto.PageResponse.of(page);
    }
}
