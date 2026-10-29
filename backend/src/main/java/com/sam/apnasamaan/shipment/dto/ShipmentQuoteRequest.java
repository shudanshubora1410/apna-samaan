package com.sam.apnasamaan.shipment.dto;

import com.sam.apnasamaan.shipment.entity.PackageCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "Side-effect-free shipment quote request (server calculates road distance and INR fare)")
public record ShipmentQuoteRequest(
        @Schema(example = "28.6315", description = "Pickup latitude within India (6.0 to 38.0)")
        @NotNull(message = "Pickup latitude is required")
        @DecimalMin(value = "6.0", message = "Pickup latitude must be within India (6.0 to 38.0)")
        @DecimalMax(value = "38.0", message = "Pickup latitude must be within India (6.0 to 38.0)")
        Double pickupLat,

        @Schema(example = "77.2167", description = "Pickup longitude within India (68.0 to 98.0)")
        @NotNull(message = "Pickup longitude is required")
        @DecimalMin(value = "68.0", message = "Pickup longitude must be within India (68.0 to 98.0)")
        @DecimalMax(value = "98.0", message = "Pickup longitude must be within India (68.0 to 98.0)")
        Double pickupLng,

        @Schema(example = "28.4950", description = "Dropoff latitude within India (6.0 to 38.0)")
        @NotNull(message = "Dropoff latitude is required")
        @DecimalMin(value = "6.0", message = "Dropoff latitude must be within India (6.0 to 38.0)")
        @DecimalMax(value = "38.0", message = "Dropoff latitude must be within India (6.0 to 38.0)")
        Double dropLat,

        @Schema(example = "77.0895", description = "Dropoff longitude within India (68.0 to 98.0)")
        @NotNull(message = "Dropoff longitude is required")
        @DecimalMin(value = "68.0", message = "Dropoff longitude must be within India (68.0 to 98.0)")
        @DecimalMax(value = "98.0", message = "Dropoff longitude must be within India (68.0 to 98.0)")
        Double dropLng,

        @Schema(example = "120.00", description = "Total package weight in kilograms")
        @NotNull(message = "Package weight (kg) is required")
        @DecimalMin(value = "0.10", message = "Package weight must be at least 0.10 kg")
        @DecimalMax(value = "3500.00", message = "Package weight cannot exceed 3500.00 kg")
        BigDecimal weightKg,

        @Schema(example = "110", description = "Package length in centimeters")
        @NotNull(message = "Package length (cm) is required")
        @Min(value = 1, message = "Package length must be at least 1 cm")
        @Max(value = 420, message = "Package length cannot exceed 420 cm")
        Integer lengthCm,

        @Schema(example = "80", description = "Package width in centimeters")
        @NotNull(message = "Package width (cm) is required")
        @Min(value = 1, message = "Package width must be at least 1 cm")
        @Max(value = 420, message = "Package width cannot exceed 420 cm")
        Integer widthCm,

        @Schema(example = "90", description = "Package height in centimeters")
        @NotNull(message = "Package height (cm) is required")
        @Min(value = 1, message = "Package height must be at least 1 cm")
        @Max(value = 420, message = "Package height cannot exceed 420 cm")
        Integer heightCm,

        @Schema(example = "HOUSEHOLD")
        @NotNull(message = "Package category is required")
        PackageCategory packageCategory,

        @Schema(example = "Washing machine and 3 packed cartons")
        @Size(max = 255, message = "Package description must be 255 characters or fewer")
        String packageDescription,

        @Schema(example = "true")
        boolean fragile,

        @Schema(example = "CARGO_AUTO", description = "Optional explicit vehicle code; defaults to smallest fitting vehicle")
        @Size(max = 30)
        String vehicleTypeCode,

        @Schema(example = "true", description = "Include GST on the fare breakdown")
        boolean gstApplicable
) {
}
