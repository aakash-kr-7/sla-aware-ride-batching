package com.ridebatching.trip;

public class InvalidRouteException extends RuntimeException {
    public InvalidRouteException(String message) {
        super(message);
    }
}
