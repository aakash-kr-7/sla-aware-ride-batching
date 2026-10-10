package com.ridebatching.config;

import com.ridebatching.auth.Role;
import com.ridebatching.auth.UserAccount;
import com.ridebatching.auth.UserAccountRepository;
import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingRepository;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.booking.Employee;
import com.ridebatching.booking.EmployeeRepository;
import com.ridebatching.driver.Driver;
import com.ridebatching.driver.DriverRepository;
import com.ridebatching.driver.DriverStatus;
import com.ridebatching.geo.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Component
@Profile("demo")
public class DemoDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final UserAccountRepository userAccountRepository;
    private final EmployeeRepository employeeRepository;
    private final DriverRepository driverRepository;
    private final BookingRepository bookingRepository;
    private final PasswordEncoder passwordEncoder;

    public DemoDataInitializer(UserAccountRepository userAccountRepository,
                               EmployeeRepository employeeRepository,
                               DriverRepository driverRepository,
                               BookingRepository bookingRepository,
                               PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.employeeRepository = employeeRepository;
        this.driverRepository = driverRepository;
        this.bookingRepository = bookingRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        log.info("Initializing deterministic demo data for 'demo' profile...");

        // Clear existing data to ensure predictability on repeated startups
        bookingRepository.deleteAllInBatch();
        userAccountRepository.deleteAllInBatch();
        employeeRepository.deleteAllInBatch();
        driverRepository.deleteAllInBatch();

        String safeDemoPassword = passwordEncoder.encode("demo123");

        // 1. Create Employees & Users (Admin, Approver, Employee)
        Employee empAlice = employeeRepository.save(new Employee(UUID.randomUUID(), "EMP-A", "Alice Employee", "alice@demo.local"));
        Employee empBob = employeeRepository.save(new Employee(UUID.randomUUID(), "EMP-B", "Bob Employee", "bob@demo.local"));
        
        userAccountRepository.save(new UserAccount(UUID.randomUUID(), "alice", safeDemoPassword, Role.EMPLOYEE, empAlice.getId()));
        userAccountRepository.save(new UserAccount(UUID.randomUUID(), "bob", safeDemoPassword, Role.EMPLOYEE, empBob.getId()));
        userAccountRepository.save(new UserAccount(UUID.randomUUID(), "admin", safeDemoPassword, Role.ADMIN, null));
        userAccountRepository.save(new UserAccount(UUID.randomUUID(), "approver", safeDemoPassword, Role.APPROVER, null));

        // 2. Create Drivers with multiple capacities (Available and on break)
        Driver driverAvailable = new Driver(UUID.randomUUID(), "Dave Driver", 4);
        driverAvailable.updateLocation(new GeoPoint(12.971598, 77.594562)); // Central point
        driverAvailable.goOnline();
        driverRepository.save(driverAvailable);

        Driver driverOnBreak = new Driver(UUID.randomUUID(), "Derek OnBreak", 6);
        driverOnBreak.updateLocation(new GeoPoint(12.971598, 77.594562));
        driverOnBreak.goOnline();
        driverOnBreak.startBreak();
        driverRepository.save(driverOnBreak);

        // 4. Create Serviceable Zone Requests (Compatible and Incompatible)
        Instant now = Instant.now();
        Instant slaDeadline = now.plus(30, ChronoUnit.MINUTES);
        
        // Compatible ride requests (Alice and Bob going from roughly same origin to same destination)
        Booking b1 = new Booking(UUID.randomUUID(), empAlice, 
                new GeoPoint(12.9352, 77.6245), // Koramangala
                new GeoPoint(12.9716, 77.5946), // MG Road
                now, BookingStatus.PENDING, slaDeadline, now, UUID.randomUUID().toString());
                
        Booking b2 = new Booking(UUID.randomUUID(), empBob, 
                new GeoPoint(12.9360, 77.6250), // Koramangala (very close to Alice)
                new GeoPoint(12.9720, 77.5950), // MG Road (very close to Alice)
                now, BookingStatus.PENDING, slaDeadline, now, UUID.randomUUID().toString());

        // Incompatible ride request (Far away origin/destination)
        Employee empCharlie = employeeRepository.save(new Employee(UUID.randomUUID(), "EMP-C", "Charlie Far", "charlie@demo.local"));
        userAccountRepository.save(new UserAccount(UUID.randomUUID(), "charlie", safeDemoPassword, Role.EMPLOYEE, empCharlie.getId()));
        
        Booking b3 = new Booking(UUID.randomUUID(), empCharlie, 
                new GeoPoint(13.0827, 80.2707), // Very far (Chennai)
                new GeoPoint(13.0604, 80.2496), 
                now, BookingStatus.PENDING, slaDeadline, now, UUID.randomUUID().toString());

        bookingRepository.save(b1);
        bookingRepository.save(b2);
        bookingRepository.save(b3);

        log.info("Demo data initialization complete.");
        log.info("Try logging in with user: 'alice' / pass: 'demo123'");
    }
}
