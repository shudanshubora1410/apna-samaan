package com.sam.apnasamaan.auth.security;

import com.sam.apnasamaan.auth.config.JwtProperties;
import com.sam.apnasamaan.auth.entity.Role;
import com.sam.apnasamaan.auth.entity.User;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceAndStartupValidationTest {

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtConfigTestHarness {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ValidationAutoConfiguration.class, JwtConfigTestHarness.class);

    @Test
    void startupFails_whenJwtSecretIsMissing() {
        contextRunner
                .withPropertyValues(
                        "app.jwt.access-expiration-minutes=15",
                        "app.jwt.refresh-expiration-days=7"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startupFails_whenJwtSecretIsShorterThan32Chars() {
        contextRunner
                .withPropertyValues(
                        "app.jwt.secret=too-short-secret-16",
                        "app.jwt.access-expiration-minutes=15",
                        "app.jwt.refresh-expiration-days=7"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void jwtService_generatesAndVerifiesToken_andRejectsTampering() {
        String validSecret = UUID.randomUUID().toString() + UUID.randomUUID();
        JwtService jwtService = new JwtService(new JwtProperties(validSecret, 15, 7));

        User user = User.builder()
                .id(42L)
                .name("Priya Nair")
                .email("priya@example.com")
                .role(Role.CUSTOMER)
                .enabled(true)
                .build();

        String token = jwtService.generateAccessToken(user);

        assertThat(jwtService.extractUserId(token)).isEqualTo(42L);
        assertThat(jwtService.getAccessExpirationSeconds()).isEqualTo(900L);

        // Tamper with the signature segment
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        assertThatThrownBy(() -> jwtService.extractUserId(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void jwtService_constructorRejectsShortSecretDirectly() {
        JwtProperties weakProps = new JwtProperties("short-secret", 15, 7);
        assertThatThrownBy(() -> new JwtService(weakProps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
