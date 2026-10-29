package com.sam.apnasamaan.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Reason provided by an admin when rejecting a driver application")
public record DriverRejectRequest(
        @Schema(description = "Clear explanation shown to the driver so they can re-upload valid documents",
                example = "Uploaded driving licence photo is blurry; please upload a clear scan or photo.")
        @NotBlank(message = "Rejection reason is required")
        @Size(min = 5, max = 500, message = "Rejection reason must be between 5 and 500 characters")
        String reason
) {
}
