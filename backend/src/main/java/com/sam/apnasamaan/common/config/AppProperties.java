package com.sam.apnasamaan.common.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Type-safe binding for custom `app.*` settings in application.yml.
 * Validated on startup so misconfiguration fails fast before serving traffic.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String version,
        @NotBlank @Email String supportEmail,
        Cors cors
) {
    public record Cors(@NotEmpty List<String> allowedOrigins) {
    }
}
