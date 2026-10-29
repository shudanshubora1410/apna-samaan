package com.sam.apnasamaan.payment.service;

import com.sam.apnasamaan.payment.dto.PaymentOrderResponse;
import com.sam.apnasamaan.payment.dto.PaymentResponse;
import com.sam.apnasamaan.payment.entity.Payment;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {

    public PaymentOrderResponse toOrderResponse(Payment payment, String publicKeyId) {
        return new PaymentOrderResponse(
                payment.getId(),
                payment.getShipment().getId(),
                payment.getShipment().getTrackingCode(),
                payment.getRazorpayOrderId(),
                publicKeyId,
                payment.getAmountInr(),
                payment.getAmountPaise(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getCustomer().getName(),
                payment.getCustomer().getEmail(),
                payment.getCustomer().getPhone()
        );
    }

    public PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getShipment().getId(),
                payment.getShipment().getTrackingCode(),
                payment.getShipment().getStatus(),
                payment.getCustomer().getId(),
                payment.getCustomer().getName(),
                payment.getCustomer().getEmail(),
                payment.getRazorpayOrderId(),
                payment.getRazorpayPaymentId(),
                payment.getAmountInr(),
                payment.getAmountPaise(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getFailureReason(),
                payment.getRazorpayRefundId(),
                payment.getRefundedAmountInr(),
                payment.getRefundReason(),
                payment.getCapturedAt(),
                payment.getRefundedAt(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
