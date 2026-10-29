package com.sam.apnasamaan.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Local simulation-only checkout request (enabled strictly when RAZORPAY_SIMULATION_ENABLED=true)")
public record PaymentSimulateRequest(
        @NotNull(message = "Shipment ID is required")
        Long shipmentId,

        @NotBlank(message = "Razorpay order ID is required")
        String razorpayOrderId
) {
}
