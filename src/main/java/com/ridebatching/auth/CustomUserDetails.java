package com.ridebatching.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.UUID;

public class CustomUserDetails extends User {

    private final UUID employeeId;

    public CustomUserDetails(String username, String password, Collection<? extends GrantedAuthority> authorities, UUID employeeId) {
        super(username, password, authorities);
        this.employeeId = employeeId;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }
}
