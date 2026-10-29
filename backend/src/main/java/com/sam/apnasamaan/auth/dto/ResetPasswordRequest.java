package com.sam.apnasamaan.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Payload to consume a single-use reset token and set a new password")
public record ResetPasswordRequest(
        @Schema(description = "Single-use password reset token from the emailed link")
        @NotBlank(message = "Reset token is required")
        String token,

        @Schema(description = "New password (8–72 characters)", example = "NewStrongPass@456")
        @NotBlank(message = "New password is required")
        @Size(min = 8, max = 72, message = "New password must be between 8 and 72 characters")
        String newPassword
) {
}
