package com.ridebatching.dev;

import com.ridebatching.auth.*;
import com.ridebatching.booking.*;
import com.ridebatching.driver.*;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.zone.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Disposable reference data, loaded only by an explicitly selected local profile. */
@Component
@Profile("local-fixtures")
public class LocalFixtures implements CommandLineRunner {
    public static final String PASSWORD = "local-only-password";
    private static final Logger log = LoggerFactory.getLogger(LocalFixtures.class);
    private final JdbcTemplate jdbc;
    private final EmployeeRepository employees;
    private final UserAccountRepository accounts;
    private final DriverRepository drivers;
    private final ServiceableZoneRepository zones;
    private final PasswordEncoder encoder;
    private final String databaseName;

    public LocalFixtures(JdbcTemplate jdbc, EmployeeRepository employees, UserAccountRepository accounts,
                         DriverRepository drivers, ServiceableZoneRepository zones, PasswordEncoder encoder,
                         @Value("${app.local.fixtures.database-name:ride_batching_local}") String databaseName) {
        this.jdbc = jdbc;
        this.employees = employees;
        this.accounts = accounts;
        this.drivers = drivers;
        this.zones = zones;
        this.encoder = encoder;
        this.databaseName = databaseName;
    }

    public static UUID id(int number) { return new UUID(0, number); }

    @Override
    @Transactional
    public void run(String... args) {
        if (!databaseName.equals(jdbc.queryForObject("select current_database()", String.class))) {
            throw new IllegalStateException("Local fixtures require the isolated " + databaseName + " database");
        }
        // Concurrent local starts must not both observe an empty database and seed it.
        jdbc.execute("select pg_advisory_xact_lock(7112026)");
        if (accounts.findById(id(90)).isPresent()) {
            verifyExistingFixtures();
            log.info("Local reference data already exists; bookings, routes and driver state retained");
            return;
        }
        for (String table : new String[]{"user_account", "employees", "drivers", "serviceable_zones",
                "bookings", "trips", "driver_offers", "sla_escalation"}) {
            if (jdbc.queryForObject("select count(*) from " + table, Long.class) != 0) {
                throw new IllegalStateException("Local fixtures will not modify existing business data. "
                        + "Reset only the ride-batching-local Compose volume to start again.");
            }
        }
        for (int number = 1; number <= 5; number++) {
            employees.save(new Employee(id(number), "LOCAL-" + number, "Local rider " + number,
                    "rider" + number + "@example.invalid"));
        }
        employees.flush();
        accounts.save(new UserAccount(id(90), "local-admin", encoder.encode(PASSWORD), Role.ADMIN, null));
        for (int number = 1; number <= 5; number++) {
            accounts.save(new UserAccount(id(90 + number), "local-rider-" + number,
                    encoder.encode(PASSWORD), Role.EMPLOYEE, id(number)));
        }
        zones.save(new ServiceableZone("Local central pickup", 12.9716, 77.5946, 5000, true));
        zones.save(new ServiceableZone("Local northern pickup", 13.0716, 77.5946, 3000, true));
        addDriver(101, "Local nearest driver", 4, 12.9716, true);
        addDriver(102, "Local backup driver", 4, 12.9800, true);
        addDriver(103, "Local two-seat driver", 2, 12.9716, true);
        addDriver(104, "Local offline driver", 4, 12.9716, false);
        log.info("Local fixtures loaded: five employee accounts, one admin, two zones and four drivers. "
                + "No bookings or trips created. Credentials are disposable; see docs/local-development.md.");
    }

    private void addDriver(int number, String name, int seats, double latitude, boolean online) {
        Driver driver = new Driver(id(number), name, seats);
        driver.updateLocation(new GeoPoint(latitude, 77.5946));
        if (online) driver.goOnline();
        drivers.save(driver);
    }

    private void verifyExistingFixtures() {
        UserAccount admin = accounts.findById(id(90)).orElseThrow();
        boolean complete = "local-admin".equals(admin.getUsername()) && admin.getRole() == Role.ADMIN
                && encoder.matches(PASSWORD, admin.getPasswordHash())
                && zones.count() == 2 && employees.count() == 5 && drivers.count() == 4 && accounts.count() == 6;
        for (int number = 1; number <= 5; number++) {
            UserAccount rider = accounts.findById(id(90 + number)).orElse(null);
            complete &= employees.existsById(id(number)) && rider != null
                    && ("local-rider-" + number).equals(rider.getUsername())
                    && rider.getRole() == Role.EMPLOYEE && id(number).equals(rider.getEmployeeId())
                    && encoder.matches(PASSWORD, rider.getPasswordHash());
        }
        for (int number = 101; number <= 104; number++) complete &= drivers.existsById(id(number));
        if (!complete) throw new IllegalStateException("Local fixtures are incomplete or changed; "
                + "reset the disposable ride-batching-local database instead of overwriting data.");
    }
}
