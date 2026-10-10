package com.ridebatching.auth;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile("!test")
public class LocalDevBootstrap implements CommandLineRunner {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public LocalDevBootstrap(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userAccountRepository.count() == 0) {
            UserAccount admin = new UserAccount(
                    UUID.randomUUID(),
                    "admin",
                    passwordEncoder.encode("admin"),
                    Role.ADMIN,
                    null
            );
            userAccountRepository.save(admin);
            System.out.println("Default admin user created with username 'admin' and password 'admin'");
        }
    }
}
