package com.sam.apnasamaan.shipment.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.maps")
public record MapsProperties(
        @NotBlank String nominatimBaseUrl,
        @NotBlank String osrmBaseUrl,
        @NotBlank String userAgent,
        @Min(500) int timeoutMillis,
        @DecimalMin("1.0") double haversineRoadFactor,
        @DecimalMin("5.0") double averageSpeedKmh
) {
}
