package com.sam.apnasamaan.auth.dto;

import com.sam.apnasamaan.auth.entity.AuthProvider;
import com.sam.apnasamaan.auth.entity.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Public user representation — never includes passwordHash or internal tokens.
 */
@Schema(description = "Safe summary of an authenticated user")
public record UserSummaryResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "Aarav Sharma") String name,
        @Schema(example = "aarav.sharma@example.com") String email,
        @Schema(example = "9876543210") String phone,
        @Schema(example = "CUSTOMER") Role role,
        @Schema(example = "LOCAL") AuthProvider authProvider,
        @Schema(example = "true") boolean enabled,
        @Schema(example = "2026-10-25T05:30:00Z") Instant createdAt
) {
}
