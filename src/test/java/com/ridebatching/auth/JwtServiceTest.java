package com.ridebatching.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {
    private final String secret = "test-only-signing-key-32-characters-minimum";
    private final JwtService service = new JwtService(secret, 60_000);

    @Test void signedTokenWithoutExpirationMustBeInvalid() {
        String token = Jwts.builder().subject("employee").signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
        assertThat(service.isTokenValid(token, "employee")).isFalse();
    }
    @Test void signedTokenWithoutSubjectMustBeInvalid() {
        String token = Jwts.builder().expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
        assertThat(service.isTokenValid(token, "employee")).isFalse();
    }
    @Test void validGeneratedTokenRequiresMatchingAccount() {
        String token = service.generateToken("employee", Role.EMPLOYEE);
        assertThat(service.isTokenValid(token, "employee")).isTrue();
        assertThat(service.isTokenValid(token, "other")).isFalse();
    }
    @Test void wrongSigningKeyMustBeRejected() {
        String token = new JwtService("another-test-only-key-32-characters-minimum", 60_000).generateToken("employee", Role.EMPLOYEE);
        assertThatThrownBy(() -> service.isTokenValid(token, "employee")).isInstanceOf(io.jsonwebtoken.JwtException.class);
    }
    @Test void expiredTokenMustBeRejected() {
        String token = Jwts.builder().subject("employee").expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
        assertThatThrownBy(() -> service.isTokenValid(token, "employee")).isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
    }
}
