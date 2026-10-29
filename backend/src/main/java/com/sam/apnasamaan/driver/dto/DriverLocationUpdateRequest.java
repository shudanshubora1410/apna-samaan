package com.sam.apnasamaan.driver.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Live GPS coordinates reported by an online/assigned driver (~10s polling)")
public record DriverLocationUpdateRequest(
        @Schema(example = "28.6289", description = "Latitude within India (6.0 to 38.0)")
        @NotNull(message = "Latitude is required")
        @DecimalMin(value = "6.0", message = "Latitude must be within India (6.0 to 38.0)")
        @DecimalMax(value = "38.0", message = "Latitude must be within India (6.0 to 38.0)")
        Double lat,

        @Schema(example = "77.2150", description = "Longitude within India (68.0 to 98.0)")
        @NotNull(message = "Longitude is required")
        @DecimalMin(value = "68.0", message = "Longitude must be within India (68.0 to 98.0)")
        @DecimalMax(value = "98.0", message = "Longitude must be within India (68.0 to 98.0)")
        Double lng
) {
}
