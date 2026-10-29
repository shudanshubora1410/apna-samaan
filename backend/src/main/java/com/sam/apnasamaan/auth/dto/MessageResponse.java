package com.sam.apnasamaan.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Simple confirmation message")
public record MessageResponse(
        @Schema(example = "Operation completed successfully") String message
) {
}
