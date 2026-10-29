package com.sam.apnasamaan.customer.dto;

import com.sam.apnasamaan.auth.entity.AuthProvider;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Customer profile details")
public record CustomerProfileResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "Aarav Sharma") String name,
        @Schema(example = "aarav.sharma@example.com") String email,
        @Schema(example = "9876543210") String phone,
        @Schema(example = "LOCAL") AuthProvider authProvider,
        @Schema(example = "false") boolean googleLinked,
        @Schema(example = "2026-10-25T05:30:00Z") Instant createdAt,
        @Schema(example = "2026-10-25T06:00:00Z") Instant updatedAt
) {
}
