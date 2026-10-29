package com.sam.apnasamaan.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to revoke a refresh token on logout")
public record LogoutRequest(
        @Schema(description = "Opaque refresh token to revoke")
        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {
}
