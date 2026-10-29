package com.sam.apnasamaan.driver.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Toggle driver online or offline status")
public record DriverAvailabilityRequest(
        @Schema(description = "true to go ONLINE (requires APPROVED status), false to go OFFLINE", example = "true")
        @NotNull(message = "online flag is required")
        Boolean online
) {
}
