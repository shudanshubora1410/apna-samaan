package com.sam.apnasamaan.shipment.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.matching")
public record MatchingProperties(
        @Min(1) long offerTimeoutSeconds,
        @Min(1) long searchTimeoutSeconds,
        @Min(250) long sweepIntervalMillis,
        boolean schedulerEnabled
) {
}
