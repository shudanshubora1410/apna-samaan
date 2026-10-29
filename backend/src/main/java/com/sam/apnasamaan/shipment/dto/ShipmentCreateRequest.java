package com.sam.apnasamaan.shipment.dto;

import com.sam.apnasamaan.shipment.entity.PackageCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "Customer booking request — server computes distance and INR fare deterministically")
public record ShipmentCreateRequest(
        @Schema(example = "Block A, Connaught Place, New Delhi 110001")
        @NotBlank(message = "Pickup address is required")
        @Size(min = 5, max = 300, message = "Pickup address must be between 5 and 300 characters")
        String pickupAddress,

        @Schema(example = "Delhi")
        @NotBlank(message = "Pickup city is required")
        @Size(min = 2, max = 80, message = "Pickup city must be between 2 and 80 characters")
        String pickupCity,

        @Schema(example = "28.6315")
        @NotNull(message = "Pickup latitude is required")
        @DecimalMin(value = "6.0", message = "Pickup latitude must be within India (6.0 to 38.0)")
        @DecimalMax(value = "38.0", message = "Pickup latitude must be within India (6.0 to 38.0)")
        Double pickupLat,

        @Schema(example = "77.2167")
        @NotNull(message = "Pickup longitude is required")
        @DecimalMin(value = "68.0", message = "Pickup longitude must be within India (68.0 to 98.0)")
        @DecimalMax(value = "98.0", message = "Pickup longitude must be within India (68.0 to 98.0)")
        Double pickupLng,

        @Schema(example = "Rohan Sharma")
        @NotBlank(message = "Pickup contact name is required")
        @Size(min = 2, max = 100, message = "Pickup contact name must be between 2 and 100 characters")
        String pickupContactName,

        @Schema(example = "9876543210")
        @NotBlank(message = "Pickup contact phone is required")
        @Pattern(regexp = "^[6-9]\\d{9}$", message = "Pickup contact phone must be a valid 10-digit Indian mobile number")
        String pickupContactPhone,

        @Schema(example = "DLF Cyber Hub, Phase 2, Gurugram 122002")
        @NotBlank(message = "Dropoff address is required")
        @Size(min = 5, max = 300, message = "Dropoff address must be between 5 and 300 characters")
        String dropAddress,

        @Schema(example = "Gurugram")
        @NotBlank(message = "Dropoff city is required")
        @Size(min = 2, max = 80, message = "Dropoff city must be between 2 and 80 characters")
        String dropCity,

        @Schema(example = "28.4950")
        @NotNull(message = "Dropoff latitude is required")
        @DecimalMin(value = "6.0", message = "Dropoff latitude must be within India (6.0 to 38.0)")
        @DecimalMax(value = "38.0", message = "Dropoff latitude must be within India (6.0 to 38.0)")
        Double dropLat,

        @Schema(example = "77.0895")
        @NotNull(message = "Dropoff longitude is required")
        @DecimalMin(value = "68.0", message = "Dropoff longitude must be within India (68.0 to 98.0)")
        @DecimalMax(value = "98.0", message = "Dropoff longitude must be within India (68.0 to 98.0)")
        Double dropLng,

        @Schema(example = "Priya Verma")
        @NotBlank(message = "Dropoff contact name is required")
        @Size(min = 2, max = 100, message = "Dropoff contact name must be between 2 and 100 characters")
        String dropContactName,

        @Schema(example = "9812345678")
        @NotBlank(message = "Dropoff contact phone is required")
        @Pattern(regexp = "^[6-9]\\d{9}$", message = "Dropoff contact phone must be a valid 10-digit Indian mobile number")
        String dropContactPhone,

        @Schema(example = "Washing machine and 3 packed cartons")
        @NotBlank(message = "Package description is required")
        @Size(min = 3, max = 255, message = "Package description must be between 3 and 255 characters")
        String packageDescription,

        @Schema(example = "HOUSEHOLD")
        @NotNull(message = "Package category is required")
        PackageCategory packageCategory,

        @Schema(example = "120.00")
        @NotNull(message = "Package weight (kg) is required")
        @DecimalMin(value = "0.10", message = "Package weight must be at least 0.10 kg")
        @DecimalMax(value = "3500.00", message = "Package weight cannot exceed 3500.00 kg")
        BigDecimal weightKg,

        @Schema(example = "110")
        @NotNull(message = "Package length (cm) is required")
        @Min(value = 1, message = "Package length must be at least 1 cm")
        @Max(value = 420, message = "Package length cannot exceed 420 cm")
        Integer lengthCm,

        @Schema(example = "80")
        @NotNull(message = "Package width (cm) is required")
        @Min(value = 1, message = "Package width must be at least 1 cm")
        @Max(value = 420, message = "Package width cannot exceed 420 cm")
        Integer widthCm,

        @Schema(example = "90")
        @NotNull(message = "Package height (cm) is required")
        @Min(value = 1, message = "Package height must be at least 1 cm")
        @Max(value = 420, message = "Package height cannot exceed 420 cm")
        Integer heightCm,

        @Schema(example = "true")
        boolean fragile,

        @Schema(example = "CARGO_AUTO", description = "Optional explicit vehicle code; defaults to smallest fitting vehicle")
        @Size(max = 30)
        String vehicleTypeCode,

        @Schema(example = "true")
        boolean gstApplicable
) {
}
