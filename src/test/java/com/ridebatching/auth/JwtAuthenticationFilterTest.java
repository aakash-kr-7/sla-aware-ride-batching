package com.ridebatching.auth;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    private final JwtService jwt = mock(JwtService.class);
    private final CustomUserDetailsService users = mock(CustomUserDetailsService.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt, users);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.clearContext();
        request.addHeader("Authorization", "Bearer valid");
        when(jwt.extractUsername("valid")).thenReturn("employee");
        when(jwt.isTokenValid("valid", "employee")).thenReturn(true);
        when(users.loadUserByUsername("employee")).thenReturn(User.withUsername("employee").password("unused").roles("EMPLOYEE").build());
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void mustNotTurnDownstreamServletFailureIntoUnauthorized() {
        ServletException failure = new ServletException("injected downstream failure");
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> { throw failure; })).isSameAs(failure);
        assertThat(response.getStatus()).isNotEqualTo(401);
    }
    @Test
    void mustNotSwallowDownstreamRuntimeFailure() {
        IllegalStateException failure = new IllegalStateException("injected downstream failure");
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> { throw failure; })).isSameAs(failure);
        assertThat(response.getStatus()).isNotEqualTo(401);
    }
    @Test
    void accountDatabaseFailureMustPropagate() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database unavailable");
        when(users.loadUserByUsername("employee")).thenThrow(failure);
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> fail("chain must not execute"))).isSameAs(failure);
        assertThat(response.getStatus()).isNotEqualTo(401);
    }
    @Test
    void malformedTokenMustReturnNonLeakingUnauthorized() throws Exception {
        when(jwt.extractUsername("valid")).thenThrow(new io.jsonwebtoken.MalformedJwtException("internal parsing detail"));
        filter.doFilter(request, response, (req, res) -> fail("chain must not execute"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid or expired token").doesNotContain("internal parsing detail");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
