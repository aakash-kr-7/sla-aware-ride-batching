package com.ridebatching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseIntegrationTest extends AbstractPostgresIntegrationTest {

    private final JdbcClient jdbcClient;

    DatabaseIntegrationTest(@Autowired JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Test
    void shouldExecuteDatabaseQueryAndVerifyFlywaySchemaHistory() {
        Integer pingResult = jdbcClient.sql("SELECT 1")
                .query(Integer.class)
                .single();
        assertThat(pingResult).isEqualTo(1);

        Boolean flywayHistoryExists = jdbcClient.sql(
                "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'flyway_schema_history')"
        ).query(Boolean.class).single();
        assertThat(flywayHistoryExists).isTrue();

        Integer appliedMigrations = jdbcClient.sql(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true"
        ).query(Integer.class).single();
        assertThat(appliedMigrations).isGreaterThanOrEqualTo(1);
    }
}
