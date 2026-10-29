package com.sam.apnasamaan.payment.dto;

import com.sam.apnasamaan.payment.entity.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Razorpay Checkout initialization payload (contains public keyId only, never secrets)")
public record PaymentOrderResponse(
        Long paymentId,
        Long shipmentId,
        String trackingCode,
        String razorpayOrderId,
        String razorpayKeyId,
        BigDecimal amountInr,
        long amountPaise,
        String currency,
        PaymentStatus status,
        String customerName,
        String customerEmail,
        String customerPhone
) {
}
