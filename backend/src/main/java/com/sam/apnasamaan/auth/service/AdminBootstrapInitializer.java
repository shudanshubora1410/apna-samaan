package com.sam.apnasamaan.auth.service;

import com.sam.apnasamaan.auth.config.AdminProperties;
import com.sam.apnasamaan.auth.entity.Role;
import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.auth.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Creates the initial ADMIN account on first boot from ADMIN_EMAIL and ADMIN_PASSWORD
 * environment variables. Skips creation idempotently on subsequent restarts.
 */
@Slf4j
@Component
public class AdminBootstrapInitializer implements ApplicationRunner {

    private final AdminProperties adminProperties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapInitializer(
            AdminProperties adminProperties,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.adminProperties = adminProperties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String adminEmail = adminProperties.email().trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmailIgnoreCase(adminEmail)) {
            log.info("Admin bootstrap check: account {} already exists — skipping creation", adminEmail);
            return;
        }

        String rawPassword = adminProperties.password();
        if (rawPassword == null || rawPassword.isBlank() || rawPassword.length() < 8) {
            throw new IllegalStateException(
                    "ADMIN_PASSWORD must be set (minimum 8 characters) to create the initial admin account"
            );
        }

        User admin = User.builder()
                .name("Apna Samaan Admin")
                .email(adminEmail)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(Role.ADMIN)
                .enabled(true)
                .build();

        userRepository.save(admin);
        log.info("Bootstrapped initial ADMIN account ({})", adminEmail);
    }
}
