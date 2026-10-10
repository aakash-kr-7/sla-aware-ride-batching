package com.ridebatching.dev;

import com.ridebatching.auth.Role;
import com.ridebatching.auth.UserAccount;
import com.ridebatching.auth.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile("local")
@ConditionalOnProperty(name = "app.local.admin.enabled", havingValue = "true")
public class LocalDevBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDevBootstrap.class);

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public LocalDevBootstrap(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder,
                             @Value("${app.local.admin.username}") String username,
                             @Value("${app.local.admin.password}") String password) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalArgumentException("Local admin bootstrap requires a username and password");
        }
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(String... args) {
        if (userAccountRepository.count() == 0) {
            UserAccount admin = new UserAccount(
                    UUID.randomUUID(),
                    username,
                    passwordEncoder.encode(password),
                    Role.ADMIN,
                    null
            );
            userAccountRepository.save(admin);
            log.info("Local admin account created");
        }
    }
}
