package com.sam.apnasamaan.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "Creates or reuses a Razorpay Test Mode order for a PENDING_PAYMENT shipment")
public record PaymentOrderCreateRequest(
        @Schema(example = "1", description = "Shipment ID — server reads exact INR totalFare from MySQL")
        @NotNull(message = "Shipment ID is required")
        @Positive(message = "Shipment ID must be positive")
        Long shipmentId
) {
}
