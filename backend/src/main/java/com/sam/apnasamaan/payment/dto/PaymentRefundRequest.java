package com.sam.apnasamaan.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Reason recorded when issuing a full Razorpay refund")
public record PaymentRefundRequest(
        @Schema(example = "Admin refund for customer cancellation before driver pickup")
        @NotBlank(message = "Refund reason is required")
        @Size(min = 3, max = 300, message = "Refund reason must be between 3 and 300 characters")
        String reason
) {
}
