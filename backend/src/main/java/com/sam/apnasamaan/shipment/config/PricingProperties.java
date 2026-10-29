package com.sam.apnasamaan.shipment.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Validated
@ConfigurationProperties(prefix = "app.pricing")
public record PricingProperties(
        @NotNull
        @DecimalMin("0.00")
        @DecimalMax("50.00")
        BigDecimal gstPercent
) {
}
