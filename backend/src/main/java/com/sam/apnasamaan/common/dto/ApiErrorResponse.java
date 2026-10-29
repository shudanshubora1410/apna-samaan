package com.sam.apnasamaan.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * Standard error envelope returned across every endpoint and security filter.
 */
@Schema(description = "Consistent JSON error response returned by Apna Samaan API")
public record ApiErrorResponse(
        @Schema(description = "UTC timestamp when the error occurred", example = "2026-10-25T05:30:00Z")
        Instant timestamp,

        @Schema(description = "HTTP status code", example = "400")
        int status,

        @Schema(description = "HTTP status reason phrase", example = "Bad Request")
        String error,

        @Schema(description = "Human-readable summary of what went wrong", example = "Validation failed for one or more fields")
        String message,

        @Schema(description = "Request URI path that triggered the error", example = "/api/v1/auth/login")
        String path,

        @Schema(description = "Field-level validation errors keyed by field name (empty map when not applicable)",
                example = "{\"email\": \"must be a well-formed email address\"}")
        Map<String, String> fieldErrors
) {
}
