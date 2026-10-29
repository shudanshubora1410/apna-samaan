package com.sam.apnasamaan.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to send a 15-minute password reset link")
public record ForgotPasswordRequest(
        @Schema(description = "Email address associated with the account", example = "aarav.sharma@example.com")
        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        String email
) {
}
