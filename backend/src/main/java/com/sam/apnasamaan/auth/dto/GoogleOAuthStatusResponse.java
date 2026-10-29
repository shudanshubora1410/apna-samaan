package com.sam.apnasamaan.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Public availability metadata for Customer Google OAuth2 sign-in")
public record GoogleOAuthStatusResponse(
        @Schema(description = "True when non-placeholder GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are configured", example = "true")
        boolean enabled,

        @Schema(description = "Backend authorization entrypoint that initiates the Google OAuth2 redirect", example = "/api/v1/auth/google/authorize")
        String authorizationPath
) {
}
