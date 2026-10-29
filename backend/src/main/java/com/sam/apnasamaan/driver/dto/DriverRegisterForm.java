package com.sam.apnasamaan.driver.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Multipart form fields for registering a new driver with vehicle details")
public record DriverRegisterForm(
        @Schema(description = "Driver's full name", example = "Rajesh Kumar")
        @NotBlank(message = "Name is required")
        @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
        String name,

        @Schema(description = "Driver's email address", example = "rajesh.driver@example.com")
        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 150, message = "Email must not exceed 150 characters")
        String email,

        @Schema(description = "10-digit Indian mobile number starting with 6–9", example = "9811223344")
        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^[6-9]\\d{9}$", message = "Phone number must be a valid 10-digit Indian mobile number")
        String phone,

        @Schema(description = "Account password (8–72 characters)", example = "DriverPass@123")
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password,

        @Schema(description = "Vehicle tier code", example = "MINI_TRUCK",
                allowableValues = {"BIKE", "CARGO_AUTO", "MINI_TRUCK", "TRUCK"})
        @NotBlank(message = "Vehicle type code is required")
        String vehicleTypeCode,

        @Schema(description = "Indian RTO or BH-series registration number", example = "DL01AB1234")
        @NotBlank(message = "Vehicle registration number is required")
        @Size(min = 6, max = 20, message = "Vehicle registration number must be between 6 and 20 characters")
        String vehicleNumber,

        @Schema(description = "Operating city in India", example = "Delhi")
        @NotBlank(message = "City is required")
        @Size(min = 2, max = 80, message = "City must be between 2 and 80 characters")
        String city
) {
}
