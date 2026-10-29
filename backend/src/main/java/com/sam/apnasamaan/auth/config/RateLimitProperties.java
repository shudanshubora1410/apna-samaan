package com.sam.apnasamaan.auth.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @Positive int loginMaxRequests,
        @Positive long loginWindowSeconds,
        @Positive int forgotPasswordMaxRequests,
        @Positive long forgotPasswordWindowSeconds
) {
}
