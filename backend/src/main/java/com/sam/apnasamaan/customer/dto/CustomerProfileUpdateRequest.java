package com.sam.apnasamaan.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Payload for updating a customer's own name and phone number")
public record CustomerProfileUpdateRequest(
        @Schema(description = "Full name", example = "Aarav K. Sharma")
        @NotBlank(message = "Name is required")
        @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
        String name,

        @Schema(description = "10-digit Indian mobile number starting with 6–9", example = "9876501234")
        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^[6-9]\\d{9}$", message = "Phone number must be a valid 10-digit Indian mobile number")
        String phone
) {
}
