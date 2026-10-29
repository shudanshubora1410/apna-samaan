package com.sam.apnasamaan.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Payload for registering a new customer account")
public record CustomerRegisterRequest(
        @Schema(description = "Full name of the customer", example = "Aarav Sharma")
        @NotBlank(message = "Name is required")
        @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
        String name,

        @Schema(description = "Unique email address", example = "aarav.sharma@example.com")
        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 150, message = "Email must not exceed 150 characters")
        String email,

        @Schema(description = "10-digit Indian mobile number starting with 6–9", example = "9876543210")
        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^[6-9]\\d{9}$", message = "Phone number must be a valid 10-digit Indian mobile number")
        String phone,

        @Schema(description = "Account password (8–72 characters)", example = "StrongPass@123")
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password
) {
}
