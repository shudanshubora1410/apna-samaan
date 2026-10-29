package com.sam.apnasamaan.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Idempotent acknowledgement returned to Razorpay webhooks")
public record WebhookAckResponse(
        String status,
        String eventType,
        String message
) {
}
