package com.ridebatching.auth;

import com.ridebatching.booking.Employee;
import com.ridebatching.booking.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserAccountRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Test
    void shouldSaveAndFindUserAccount() {
        // Given an employee
        UUID employeeId = UUID.randomUUID();
        Employee employee = new Employee(employeeId, "EMP-AUTH-1", "Test Auth", "auth@example.com");
        employeeRepository.save(employee);

        // And a user account
        UUID accountId = UUID.randomUUID();
        UserAccount account = new UserAccount(
            accountId,
            "testuser",
            "$2a$10$abcdefghijklmnopqrstuv", // dummy bcrypt hash
            Role.EMPLOYEE,
            employeeId
        );

        userAccountRepository.save(account);

        // When
        Optional<UserAccount> found = userAccountRepository.findByUsername("testuser");

        // Then
        assertTrue(found.isPresent());
        assertEquals(accountId, found.get().getId());
        assertEquals(Role.EMPLOYEE, found.get().getRole());
        assertEquals(employeeId, found.get().getEmployeeId());
    }
}
