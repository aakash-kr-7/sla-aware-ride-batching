package com.ridebatching.auth;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridebatching.auth.dto.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SecurityIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void shouldDenyAccessToUnauthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/v1/bookings"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviewerPageAndAssetsArePublicButBusinessMutationsStillRequireAuthentication() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger")));
        mockMvc.perform(get("/reviewer/reviewer.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/auth/login")));
        mockMvc.perform(get("/reviewer/reviewer.css"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/batching/trigger"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/dispatch/trips/" + UUID.randomUUID() + "/advance"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAuthenticateAndIssueTokenForValidCredentials() throws Exception {
        // Setup User
        UserAccount account = new UserAccount(
                UUID.randomUUID(),
                "testadmin",
                passwordEncoder.encode("secret"),
                Role.ADMIN,
                null
        );
        userAccountRepository.save(account);

        LoginRequest login = new LoginRequest("testadmin", "secret");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void shouldRejectInvalidCredentials() throws Exception {
        LoginRequest login = new LoginRequest("nonexistent", "badpass");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized()); // Because Spring Security's auth manager returns 401 via exception handling or basic error filter
    }
}
