package com.ridebatching.booking.api;

import com.ridebatching.booking.BookingService;
import com.ridebatching.api.PageResponse;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Employee (Bookings)", description = "Endpoints for employees to create, list, and cancel their ride bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @Operation(summary = "Create or reuse a ride booking", description = "Requires an existing employee and serviceable pickup. The same employee, normalized endpoints and time slot return the existing active booking (201). New bookings are PENDING unless immediately inserted into a feasible planned trip. Outside-zone pickup returns 422. Slots and deadlines are server-assigned.")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or #employeeId == authentication.principal.employeeId")
    public BookingResponse createBooking(
            @Parameter(description = "Existing employee UUID. Must match the account's employee unless the caller is an admin.")
            @RequestHeader("X-Employee-Id") UUID employeeId,
            @Valid @RequestBody BookingRequest request) {
        return bookingService.createBooking(employeeId, request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Inspect one booking's status and trip association", description = "Use the id returned by creation or listing. Owners and admins may read it. A null tripId means no association; CONFIRMED means feasible route association, independently of driver acceptance. PICKED_UP/DROPPED are advanced through admin execution simulation after acceptance. This response does not include the trip's ordered stops.")
    @PostAuthorize("hasRole('ADMIN') or returnObject.employeeId() == authentication.principal.employeeId")
    public BookingResponse getBooking(@Parameter(description = "Booking id copied from a create/list response, not an employee or trip id.") @PathVariable UUID id) {
        return bookingService.getBooking(id);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    @Operation(summary = "Cancel a booking and update its affected trip", description = "Changes data. Cancels PENDING bookings or CONFIRMED bookings only before any pickup in their trip. Removes pickup/dropoff stops, resequences and recomputes remaining arrival estimates; flags remaining missed pickup deadlines. Once execution starts, returns 409 STATE_CONFLICT even for an unpicked rider. Repeat cancellation is idempotent. Unrelated trips are retained. The employee header must identify the booking owner, including for an admin. This is stop removal/resequencing, not a new route optimization.")
    @PreAuthorize("hasRole('ADMIN') or #employeeId == authentication.principal.employeeId")
    public BookingResponse cancelBooking(
            @Parameter(description = "UUID of the employee who owns this booking; non-admin callers must also match their account.")
            @RequestHeader("X-Employee-Id") UUID employeeId,
            @Parameter(description = "Booking id copied from a create/list response.")
            @PathVariable UUID id) {
        return bookingService.cancelBooking(employeeId, id);
    }

    @GetMapping
    @Operation(summary = "List an employee's bookings", description = "Read-only inspection of current saved state. Set X-Employee-Id to an existing employee UUID; non-admin callers must match their account. Pagination is zero-based. The list includes cancelled bookings; it is not a list of every employee or every trip.")
    @PreAuthorize("hasRole('ADMIN') or #employeeId == authentication.principal.employeeId")
    public PageResponse<BookingResponse> listBookings(
            @Parameter(description = "Employee whose bookings to read. Must match the account unless the caller is an admin.")
            @RequestHeader("X-Employee-Id") UUID employeeId,
            Pageable pageable) {
        Page<BookingResponse> page = bookingService.listBookingsByEmployee(employeeId, pageable);
        return PageResponse.of(page);
    }
}
