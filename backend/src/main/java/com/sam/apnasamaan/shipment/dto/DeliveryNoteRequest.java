package com.sam.apnasamaan.shipment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Optional operational note submitted when advancing delivery status")
public record DeliveryNoteRequest(
        @Schema(example = "Loaded washing machine securely with straps")
        @Size(max = 250, message = "Note must be 250 characters or fewer")
        String note
) {
}
