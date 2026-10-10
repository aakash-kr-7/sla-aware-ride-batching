package com.ridebatching.fleet;

public record Capacity(int seats) {
    public Capacity {
        if (seats <= 0) {
            throw new IllegalArgumentException("Vehicle capacity must be strictly positive, was: " + seats);
        }
    }
}
