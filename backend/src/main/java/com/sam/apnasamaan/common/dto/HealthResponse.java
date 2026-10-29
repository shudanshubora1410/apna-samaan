package com.sam.apnasamaan.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Application and database readiness snapshot")
public record HealthResponse(
        @Schema(description = "Overall service health", example = "UP")
        String status,

        @Schema(description = "Application name", example = "Apna Samaan API")
        String service,

        @Schema(description = "Semantic version of the running backend", example = "1.0.0")
        String version,

        @Schema(description = "MySQL connectivity status", example = "UP")
        String database,

        @Schema(description = "Current server time in UTC", example = "2026-10-25T05:30:00Z")
        Instant timestamp
) {
}
