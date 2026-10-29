package com.sam.apnasamaan.shipment.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.groq")
public record GroqProperties(
        String apiKey,
        @NotBlank String baseUrl,
        @NotBlank String model,
        @Min(500) int timeoutMillis
) {
    /**
     * Treats blank or placeholder keys as unconfigured so local/CI runs fall back cleanly
     * to deterministic rule-based vehicle confirmation without external API errors.
     */
    public boolean isConfigured() {
        return apiKey != null
                && !apiKey.isBlank()
                && !apiKey.trim().startsWith("CHANGE_ME");
    }
}
