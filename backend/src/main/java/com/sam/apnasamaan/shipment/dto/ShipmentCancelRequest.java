package com.sam.apnasamaan.shipment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Reason provided when cancelling a shipment prior to pickup")
public record ShipmentCancelRequest(
        @Schema(example = "Pickup schedule changed to next week")
        @NotBlank(message = "Cancellation reason is required")
        @Size(min = 3, max = 300, message = "Cancellation reason must be between 3 and 300 characters")
        String reason
) {
}
