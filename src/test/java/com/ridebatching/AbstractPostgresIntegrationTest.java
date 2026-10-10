package com.ridebatching;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests requiring a live PostgreSQL instance.
 *
 * Starts a single shared PostgreSQL container once per test-suite execution.
 * Spring Boot's {@link ServiceConnection} dynamically injects datasource
 * credentials into the test application context, avoiding copy-pasted container
 * definitions across integration test classes.
 */
@SpringBootTest
public abstract class AbstractPostgresIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        postgres.start();
    }
}
