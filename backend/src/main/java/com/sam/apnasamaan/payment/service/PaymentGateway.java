package com.sam.apnasamaan.payment.service;

import java.util.Map;

/**
 * Wraps Razorpay Test Mode SDK operations (orders, HMAC signature checks, and refunds).
 */
public interface PaymentGateway {

    GatewayOrder createOrder(long amountPaise, String currency, String receipt, Map<String, String> notes);

    boolean verifyCheckoutSignature(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature);

    boolean verifyWebhookSignature(String rawPayload, String webhookSignature);

    GatewayRefund refundFull(String razorpayPaymentId, long amountPaise, String reason);

    record GatewayOrder(String razorpayOrderId, String publicKeyId) {
    }

    record GatewayRefund(String razorpayRefundId) {
    }
}
