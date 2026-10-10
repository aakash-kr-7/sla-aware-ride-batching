package com.ridebatching.fleet;

import java.util.UUID;

/**
 * Domain concept representing a transport vehicle in the fleet.
 * 
 * Persistence is intentionally deferred until fleet management APIs 
 * or trip dispatch logic strictly require it.
 */
public class Vehicle {

    private final UUID id;
    private final Capacity capacity;

    public Vehicle(UUID id, int seats) {
        this.id = id;
        this.capacity = new Capacity(seats);
    }
    
    public Vehicle(UUID id, Capacity capacity) {
        if (capacity == null) {
            throw new IllegalArgumentException("Capacity must not be null");
        }
        this.id = id;
        this.capacity = capacity;
    }

    public UUID getId() { 
        return id; 
    }
    
    public Capacity getCapacity() { 
        return capacity; 
    }
}
