package com.sam.apnasamaan.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.password-reset")
public record PasswordResetProperties(
        @Positive long expirationMinutes,
        @NotBlank String frontendBaseUrl
) {
}
