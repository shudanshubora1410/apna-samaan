package com.sam.apnasamaan.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Single-use OAuth2 callback handoff ticket exchanged for JWT access and refresh tokens")
public record GoogleOAuthExchangeRequest(
        @Schema(description = "One-time ticket issued by the Google OAuth2 callback redirect")
        @NotBlank(message = "OAuth ticket is required")
        @Size(max = 200, message = "OAuth ticket is invalid")
        String ticket
) {
}
