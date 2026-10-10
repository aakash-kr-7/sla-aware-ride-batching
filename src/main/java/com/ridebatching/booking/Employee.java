package com.ridebatching.booking;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "employees")
public class Employee {

    @Id
    private UUID id;

    private String corporateId;
    private String name;
    private String email;

    protected Employee() {
        // JPA required
    }

    public Employee(UUID id, String corporateId, String name, String email) {
        this.id = id;
        this.corporateId = corporateId;
        this.name = name;
        this.email = email;
    }

    public UUID getId() { return id; }
    public String getCorporateId() { return corporateId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
}
