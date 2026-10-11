package com.ridebatching.api;

import com.ridebatching.booking.exception.BookingNotFoundException;
import com.ridebatching.booking.exception.UnserviceableZoneException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(com.ridebatching.dispatch.OfferExpiredException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleExpiredOffer(RuntimeException ex, HttpServletRequest request) {
        return new ApiErrorResponse("OFFER_EXPIRED", ex.getMessage(), Instant.now(), request.getRequestURI());
    }

    @ExceptionHandler(com.ridebatching.dispatch.DispatchNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleDispatchNotFound(RuntimeException ex, HttpServletRequest request) {
        return new ApiErrorResponse("DISPATCH_NOT_FOUND", ex.getMessage(), Instant.now(), request.getRequestURI());
    }

    @ExceptionHandler({DomainConflictException.class,
            org.springframework.dao.OptimisticLockingFailureException.class,
            org.springframework.dao.PessimisticLockingFailureException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleConflict(RuntimeException ex, HttpServletRequest request) {
        return new ApiErrorResponse("STATE_CONFLICT", "Request conflicts with the current state", Instant.now(), request.getRequestURI());
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiErrorResponse handleAccessDenied(RuntimeException ex, HttpServletRequest request) {
        return new ApiErrorResponse("FORBIDDEN", "Access denied", Instant.now(), request.getRequestURI());
    }

    @ExceptionHandler({IllegalArgumentException.class, com.ridebatching.geo.InvalidCoordinateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleInvalidArgument(RuntimeException ex, HttpServletRequest request) {
        return new ApiErrorResponse("INVALID_REQUEST", "Invalid request", Instant.now(), request.getRequestURI());
    }

    @ExceptionHandler(BookingNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleBookingNotFound(BookingNotFoundException ex, HttpServletRequest request) {
        return new ApiErrorResponse(
            "BOOKING_NOT_FOUND",
            ex.getMessage(),
            Instant.now(),
            request.getRequestURI()
        );
    }

    @ExceptionHandler(UnserviceableZoneException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiErrorResponse handleUnserviceableZone(UnserviceableZoneException ex, HttpServletRequest request) {
        return new ApiErrorResponse(
            "UNSERVICEABLE_ZONE",
            ex.getMessage(),
            Instant.now(),
            request.getRequestURI()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        return new ApiErrorResponse(
            "VALIDATION_ERROR",
            "Invalid request payload",
            Instant.now(),
            request.getRequestURI()
        );
    }
}
