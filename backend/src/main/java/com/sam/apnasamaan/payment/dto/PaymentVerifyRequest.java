package com.sam.apnasamaan.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "Browser checkout callback fields verified via server-side HMAC-SHA256")
public record PaymentVerifyRequest(
        @Schema(example = "1")
        @NotNull(message = "Shipment ID is required")
        @Positive(message = "Shipment ID must be positive")
        Long shipmentId,

        @Schema(example = "order_P9xTestOrder001")
        @NotBlank(message = "Razorpay order ID is required")
        @Size(max = 64)
        String razorpayOrderId,

        @Schema(example = "pay_P9xTestPayment001")
        @NotBlank(message = "Razorpay payment ID is required")
        @Size(max = 64)
        String razorpayPaymentId,

        @Schema(example = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
        @NotBlank(message = "Razorpay signature is required")
        @Size(max = 128)
        String razorpaySignature
) {
}
