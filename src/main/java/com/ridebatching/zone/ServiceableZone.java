package com.ridebatching.zone;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "serviceable_zones")
public class ServiceableZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private double centerLatitude;
    private double centerLongitude;
    private double radiusMeters;
    private boolean active;

    protected ServiceableZone() {
        // JPA required
    }

    public ServiceableZone(String name, double centerLatitude, double centerLongitude, double radiusMeters, boolean active) {
        this.name = name;
        this.centerLatitude = centerLatitude;
        this.centerLongitude = centerLongitude;
        this.radiusMeters = radiusMeters;
        this.active = active;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public double getCenterLatitude() { return centerLatitude; }
    public double getCenterLongitude() { return centerLongitude; }
    public double getRadiusMeters() { return radiusMeters; }
    public boolean isActive() { return active; }
}
