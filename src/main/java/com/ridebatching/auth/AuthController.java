package com.ridebatching.auth;

import com.ridebatching.auth.dto.AuthResponse;
import com.ridebatching.auth.dto.LoginRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserAccountRepository userAccountRepository;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService, UserAccountRepository userAccountRepository) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userAccountRepository = userAccountRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );

        UserAccount user = userAccountRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("Invalid user"));

        String token = jwtService.generateToken(user.getUsername(), user.getRole());
        return ResponseEntity.ok(new AuthResponse(token));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<String> handleAuthenticationException(AuthenticationException e) {
        return ResponseEntity.status(401).body("Invalid credentials");
    }
}
