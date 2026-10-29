package com.sam.apnasamaan.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "JWT access token, rotatable refresh token, and user summary")
public record AuthResponse(
        @Schema(description = "Signed 15-minute JWT access token") String accessToken,
        @Schema(description = "Opaque 7-day refresh token (store securely; rotated on every refresh)") String refreshToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(description = "Access token lifetime in seconds", example = "900") long expiresInSeconds,
        UserSummaryResponse user
) {
}
