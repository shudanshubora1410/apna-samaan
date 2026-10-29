package com.sam.apnasamaan.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to rotate a valid 7-day refresh token for a new token pair")
public record RefreshTokenRequest(
        @Schema(description = "Opaque refresh token received at login or previous refresh")
        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {
}
