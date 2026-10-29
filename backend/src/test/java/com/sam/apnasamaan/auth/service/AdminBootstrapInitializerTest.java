package com.sam.apnasamaan.auth.service;

import com.sam.apnasamaan.auth.config.AdminProperties;
import com.sam.apnasamaan.auth.entity.Role;
import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapInitializerTest {

    @Mock
    private UserRepository userRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    @Test
    void run_createsAdmin_whenNotPresent() {
        String testPass = "AdminBoot#" + UUID.randomUUID();
        AdminProperties props = new AdminProperties("shudanshubora1410@gmail.com", testPass);
        when(userRepository.existsByEmailIgnoreCase("shudanshubora1410@gmail.com")).thenReturn(false);

        AdminBootstrapInitializer initializer = new AdminBootstrapInitializer(props, userRepository, passwordEncoder);
        initializer.run(new DefaultApplicationArguments());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("shudanshubora1410@gmail.com");
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(passwordEncoder.matches(testPass, saved.getPasswordHash())).isTrue();
    }

    @Test
    void run_skipsCreation_whenAdminAlreadyExists() {
        AdminProperties props = new AdminProperties("shudanshubora1410@gmail.com", "");
        when(userRepository.existsByEmailIgnoreCase("shudanshubora1410@gmail.com")).thenReturn(true);

        AdminBootstrapInitializer initializer = new AdminBootstrapInitializer(props, userRepository, passwordEncoder);
        initializer.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any());
    }

    @Test
    void run_failsFast_whenAdminMissingAndPasswordBlank() {
        AdminProperties props = new AdminProperties("shudanshubora1410@gmail.com", "   ");
        when(userRepository.existsByEmailIgnoreCase("shudanshubora1410@gmail.com")).thenReturn(false);

        AdminBootstrapInitializer initializer = new AdminBootstrapInitializer(props, userRepository, passwordEncoder);

        assertThatThrownBy(() -> initializer.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD");
    }
}
