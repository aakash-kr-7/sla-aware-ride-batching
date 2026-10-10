package com.ridebatching.common.api;

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
