package com.ridebatching.booking.api;

import com.ridebatching.booking.BookingService;
import com.ridebatching.booking.dto.BookingRequest;
import com.ridebatching.booking.dto.BookingResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createBooking(
            @RequestHeader("X-Employee-Id") UUID employeeId,
            @Valid @RequestBody BookingRequest request) {
        return bookingService.createBooking(employeeId, request);
    }
}
