package com.sam.apnasamaan.payment.dto;

import com.sam.apnasamaan.payment.entity.PaymentStatus;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Payment and refund ledger view for a shipment")
public record PaymentResponse(
        Long id,
        Long shipmentId,
        String trackingCode,
        ShipmentStatus shipmentStatus,
        Long customerId,
        String customerName,
        String customerEmail,
        String razorpayOrderId,
        String razorpayPaymentId,
        BigDecimal amountInr,
        long amountPaise,
        String currency,
        PaymentStatus status,
        String failureReason,
        String razorpayRefundId,
        BigDecimal refundedAmountInr,
        String refundReason,
        Instant capturedAt,
        Instant refundedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
