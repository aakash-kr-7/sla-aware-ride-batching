package com.ridebatching.auth;

import com.ridebatching.AbstractPostgresIntegrationTest;
import com.ridebatching.booking.Booking;
import com.ridebatching.booking.BookingRepository;
import com.ridebatching.booking.BookingStatus;
import com.ridebatching.booking.Employee;
import com.ridebatching.booking.EmployeeRepository;
import com.ridebatching.escalation.SlaEscalation;
import com.ridebatching.escalation.SlaEscalationRepository;
import com.ridebatching.escalation.SlaEscalationStatus;
import com.ridebatching.geo.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SecurityMethodIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private SlaEscalationRepository escalationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private UUID emp1Id = UUID.randomUUID();
    private UUID emp2Id = UUID.randomUUID();
    
    private String tokenEmp1;
    private String tokenEmp2;
    private String tokenAdmin;
    private String tokenApprover;

    private UUID bookingEmp1Id = UUID.randomUUID();

    @BeforeEach
    void setup() {
        // Setup Employees
        Employee emp1 = new Employee(emp1Id, "E1", "Alice", "alice@test.com");
        Employee emp2 = new Employee(emp2Id, "E2", "Bob", "bob@test.com");
        employeeRepository.save(emp1);
        employeeRepository.save(emp2);

        // Setup UserAccounts
        UserAccount ua1 = new UserAccount(UUID.randomUUID(), "alice", passwordEncoder.encode("pass"), Role.EMPLOYEE, emp1Id);
        UserAccount ua2 = new UserAccount(UUID.randomUUID(), "bob", passwordEncoder.encode("pass"), Role.EMPLOYEE, emp2Id);
        UserAccount admin = new UserAccount(UUID.randomUUID(), "admin", passwordEncoder.encode("pass"), Role.ADMIN, null);
        UserAccount approver = new UserAccount(UUID.randomUUID(), "approver", passwordEncoder.encode("pass"), Role.APPROVER, null);
        
        userAccountRepository.save(ua1);
        userAccountRepository.save(ua2);
        userAccountRepository.save(admin);
        userAccountRepository.save(approver);

        // Generate Tokens
        tokenEmp1 = "Bearer " + jwtService.generateToken(ua1.getUsername(), ua1.getRole());
        tokenEmp2 = "Bearer " + jwtService.generateToken(ua2.getUsername(), ua2.getRole());
        tokenAdmin = "Bearer " + jwtService.generateToken(admin.getUsername(), admin.getRole());
        tokenApprover = "Bearer " + jwtService.generateToken(approver.getUsername(), approver.getRole());

        // Setup a booking for emp1
        Booking booking = new Booking(bookingEmp1Id, emp1, new GeoPoint(0,0), new GeoPoint(1,1), Instant.now(), BookingStatus.PENDING, Instant.now().plusSeconds(600), Instant.now(), "dedup1");
        bookingRepository.save(booking);
        
        // Setup Escalation
        escalationRepository.save(new SlaEscalation(UUID.randomUUID(), bookingEmp1Id, "BOOKING", "SLA at risk", SlaEscalationStatus.OPEN));
    }

    @Test
    void employeeCanListOwnBookings() throws Exception {
        mockMvc.perform(get("/api/v1/bookings")
                .header("Authorization", tokenEmp1)
                .header("X-Employee-Id", emp1Id.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void employeeCannotListOtherBookings() throws Exception {
        mockMvc.perform(get("/api/v1/bookings")
                .header("Authorization", tokenEmp1)
                .header("X-Employee-Id", emp2Id.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanListAnyBookings() throws Exception {
        mockMvc.perform(get("/api/v1/bookings")
                .header("Authorization", tokenAdmin)
                .header("X-Employee-Id", emp2Id.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void employeeCanGetOwnBooking() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/" + bookingEmp1Id)
                .header("Authorization", tokenEmp1))
                .andExpect(status().isOk());
    }

    @Test
    void employeeCannotGetOtherBooking() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/" + bookingEmp1Id)
                .header("Authorization", tokenEmp2))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanGetAnyBooking() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/" + bookingEmp1Id)
                .header("Authorization", tokenAdmin))
                .andExpect(status().isOk());
    }

    @Test
    void approverCanAccessEscalations() throws Exception {
        mockMvc.perform(get("/api/v1/escalations/open")
                .header("Authorization", tokenApprover))
                .andExpect(status().isOk());
    }

    @Test
    void employeeCannotAccessEscalations() throws Exception {
        mockMvc.perform(get("/api/v1/escalations/open")
                .header("Authorization", tokenEmp1))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanTriggerBatching() throws Exception {
        mockMvc.perform(post("/api/admin/batching/trigger")
                .header("Authorization", tokenAdmin))
                .andExpect(status().isOk()); // or 200/204 etc
    }

    @Test
    void approverCannotTriggerBatching() throws Exception {
        mockMvc.perform(post("/api/admin/batching/trigger")
                .header("Authorization", tokenApprover))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCannotCancelAnotherBookingUsingTheirOwnHeader() throws Exception {
        mockMvc.perform(delete("/api/v1/bookings/" + bookingEmp1Id)
                .header("Authorization", tokenEmp2).header("X-Employee-Id", emp2Id))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(bookingRepository.findById(bookingEmp1Id).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void employeeCannotForgeCancellationEmployeeHeader() throws Exception {
        mockMvc.perform(delete("/api/v1/bookings/" + bookingEmp1Id)
                .header("Authorization", tokenEmp2).header("X-Employee-Id", emp1Id))
                .andExpect(status().isForbidden());
        assertThat(bookingRepository.findById(bookingEmp1Id).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void invalidCancellationStateReturnsConflictWithoutChangingBooking() throws Exception {
        Booking booking = bookingRepository.findById(bookingEmp1Id).orElseThrow();
        bookingRepository.saveAndFlush(new Booking(booking.getId(), booking.getEmployee(), booking.getPickupLocation(),
                booking.getDropoffLocation(), booking.getSlotTimestamp(), BookingStatus.DROPPED,
                booking.getPickupSlaDeadline(), booking.getCreatedAt(), booking.getDeduplicationKey()));
        mockMvc.perform(delete("/api/v1/bookings/" + bookingEmp1Id)
                .header("Authorization", tokenEmp1).header("X-Employee-Id", emp1Id))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Request conflicts with the current state"));
        assertThat(bookingRepository.findById(bookingEmp1Id).orElseThrow().getStatus()).isEqualTo(BookingStatus.DROPPED);
    }

    @Test
    void invalidJwtIsRejectedWithoutLeakingParserDetails() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/" + bookingEmp1Id).header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid or expired token"));
    }

    @Test
    void employeeCannotTriggerAdminBatching() throws Exception {
        mockMvc.perform(post("/api/admin/batching/trigger").header("Authorization", tokenEmp1))
                .andExpect(status().isForbidden());
    }
}
