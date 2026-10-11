package com.ridebatching.dev;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.auth.*;
import com.ridebatching.booking.*;
import com.ridebatching.driver.*;
import com.ridebatching.zone.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "app.local.fixtures.database-name=test")
@ActiveProfiles({"test", "local-fixtures"})
class LocalFixturesIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired LocalFixtures fixtures;
    @Autowired EmployeeRepository employees;
    @Autowired UserAccountRepository accounts;
    @Autowired DriverRepository drivers;
    @Autowired ServiceableZoneRepository zones;
    @Autowired BookingRepository bookings;
    @Autowired JdbcTemplate jdbc;
    @SpyBean PasswordEncoder encoder;

    @Test void loadsUsableIdentitiesAndReferenceDataWithoutBookings() {
        fixtures.run();
        assertThat(employees.count()).isEqualTo(5);
        assertThat(accounts.count()).isEqualTo(6);
        assertThat(zones.count()).isEqualTo(2);
        assertThat(drivers.count()).isEqualTo(4);
        assertThat(bookings.count()).isZero();
        for (int number = 1; number <= 5; number++) {
            UserAccount account = accounts.findByUsername("local-rider-" + number).orElseThrow();
            assertThat(account.getEmployeeId()).isEqualTo(LocalFixtures.id(number));
            assertThat(account.getRole()).isEqualTo(Role.EMPLOYEE);
            assertThat(encoder.matches(LocalFixtures.PASSWORD, account.getPasswordHash())).isTrue();
        }
        assertThat(drivers.findById(LocalFixtures.id(104)).orElseThrow().getStatus()).isEqualTo(DriverStatus.OFFLINE);
        assertThat(drivers.findById(LocalFixtures.id(103)).orElseThrow().getVehicleCapacity()).isEqualTo(2);
    }

    @Test void restartingDoesNotResetDriverStateOrReplaceAccounts() {
        fixtures.run();
        Driver driver = drivers.findById(LocalFixtures.id(101)).orElseThrow();
        driver.goOffline(); drivers.saveAndFlush(driver);
        String hash = accounts.findByUsername("local-admin").orElseThrow().getPasswordHash();
        fixtures.run();
        assertThat(accounts.count()).isEqualTo(6);
        assertThat(accounts.findByUsername("local-admin").orElseThrow().getPasswordHash()).isEqualTo(hash);
        assertThat(drivers.findById(LocalFixtures.id(101)).orElseThrow().getStatus()).isEqualTo(DriverStatus.OFFLINE);
    }

    @Test void refusesForeignOrIncompleteDataInsteadOfRepairingIt() {
        Employee foreign = employees.saveAndFlush(new Employee(UUID.randomUUID(), "EXISTING", "Existing", "existing@example.invalid"));
        assertThatThrownBy(() -> fixtures.run()).isInstanceOf(IllegalStateException.class).hasMessageContaining("existing business data");
        assertThat(employees.findAll()).extracting(Employee::getId).containsExactly(foreign.getId());
        assertThat(accounts.count()).isZero();
        employees.deleteAll();
        fixtures.run();
        accounts.deleteById(LocalFixtures.id(95));
        assertThatThrownBy(() -> fixtures.run()).isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete or changed");
        assertThat(accounts.count()).isEqualTo(5);
    }

    @Test void aPartialSeedFailureRollsBackAndCanBeRetried() {
        doThrow(new IllegalStateException("Injected password encoding failure")).when(encoder).encode(LocalFixtures.PASSWORD);
        try {
            assertThatThrownBy(() -> fixtures.run()).hasMessageContaining("Injected password");
            assertThat(employees.count()).isZero();
            assertThat(accounts.count()).isZero();
            assertThat(zones.count()).isZero();
            assertThat(drivers.count()).isZero();
        } finally { reset(encoder); }
        fixtures.run();
        assertThat(accounts.count()).isEqualTo(6);
    }

    @Test void refusesADifferentDatabaseBeforeWritingAnything() {
        LocalFixtures wrongDatabase = new LocalFixtures(jdbc, employees, accounts, drivers, zones, encoder, "another_database");
        assertThatThrownBy(() -> wrongDatabase.run()).hasMessageContaining("isolated another_database");
        assertThat(employees.count()).isZero();
    }
}
