package com.sam.apnasamaan.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Enforces at startup that JWT_SECRET is present and at least 32 characters (256 bits for HS256).
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank(message = "JWT_SECRET must be set")
        @Size(min = 32, message = "JWT_SECRET must be at least 32 characters long")
        String secret,

        @Positive
        long accessExpirationMinutes,

        @Positive
        long refreshExpirationDays
) {
}
