package com.sam.apnasamaan.payment.service;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Refund;
import com.razorpay.Utils;
import com.sam.apnasamaan.common.exception.ServiceUnavailableException;
import com.sam.apnasamaan.payment.config.RazorpayProperties;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

@Slf4j
@Service
public class RazorpayPaymentGateway implements PaymentGateway {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final RazorpayProperties properties;
    private final BiFunction<String, String, RazorpayClient> clientFactory;

    @Autowired
    public RazorpayPaymentGateway(RazorpayProperties properties) {
        this(properties, RazorpayPaymentGateway::createSdkClient);
    }

    // Package-private constructor allows unit tests to inject a mocked RazorpayClient factory
    RazorpayPaymentGateway(
            RazorpayProperties properties,
            BiFunction<String, String, RazorpayClient> clientFactory
    ) {
        this.properties = properties;
        this.clientFactory = clientFactory;
    }

    @Override
    public GatewayOrder createOrder(long amountPaise, String currency, String receipt, Map<String, String> notes) {
        if (properties.hasLiveCredentials()) {
            try {
                RazorpayClient client = clientFactory.apply(
                        properties.effectiveKeyId(),
                        properties.effectiveKeySecret()
                );

                JSONObject orderRequest = new JSONObject();
                orderRequest.put("amount", amountPaise);
                orderRequest.put("currency", currency);
                orderRequest.put("receipt", receipt);
                if (notes != null && !notes.isEmpty()) {
                    orderRequest.put("notes", new JSONObject(notes));
                }

                Order order = client.orders.create(orderRequest);
                // Bind to Object first: Entity.<T>get(key) would otherwise resolve String.valueOf(char[])!
                Object rawOrderId = order.get("id");
                String orderId = String.valueOf(rawOrderId);
                return new GatewayOrder(orderId, properties.effectiveKeyId());
            } catch (RazorpayException ex) {
                log.error("Razorpay order creation failed for receipt={}: {}", receipt, ex.getMessage());
                throw new ServiceUnavailableException("Payment gateway order creation is temporarily unavailable");
            }
        }

        if (properties.simulationEnabled()) {
            String simOrderId = "order_sim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            log.info("Razorpay simulation mode active: generated local test order receipt={}", receipt);
            return new GatewayOrder(simOrderId, properties.effectiveKeyId());
        }

        throw new ServiceUnavailableException(
                "Razorpay Test Mode credentials are not configured (set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET)"
        );
    }

    @Override
    public boolean verifyCheckoutSignature(
            String razorpayOrderId,
            String razorpayPaymentId,
            String razorpaySignature
    ) {
        String secret = properties.effectiveKeySecret();
        if (secret.isBlank()
                || isBlank(razorpayOrderId)
                || isBlank(razorpayPaymentId)
                || isBlank(razorpaySignature)) {
            return false;
        }

        String data = razorpayOrderId.trim() + "|" + razorpayPaymentId.trim();
        if (!constantTimeHmacMatches(data, secret, razorpaySignature.trim())) {
            return false;
        }

        try {
            JSONObject attributes = new JSONObject();
            attributes.put("razorpay_order_id", razorpayOrderId.trim());
            attributes.put("razorpay_payment_id", razorpayPaymentId.trim());
            attributes.put("razorpay_signature", razorpaySignature.trim());
            return Utils.verifyPaymentSignature(attributes, secret);
        } catch (RazorpayException ex) {
            log.warn("Razorpay SDK rejected checkout signature for orderId={}", razorpayOrderId);
            return false;
        }
    }

    @Override
    public boolean verifyWebhookSignature(String rawPayload, String webhookSignature) {
        String secret = properties.effectiveWebhookSecret();
        if (secret.isBlank() || rawPayload == null || isBlank(webhookSignature)) {
            return false;
        }

        if (!constantTimeHmacMatches(rawPayload, secret, webhookSignature.trim())) {
            return false;
        }

        try {
            return Utils.verifyWebhookSignature(rawPayload, webhookSignature.trim(), secret);
        } catch (RazorpayException ex) {
            log.warn("Razorpay SDK rejected webhook signature");
            return false;
        }
    }

    @Override
    public GatewayRefund refundFull(String razorpayPaymentId, long amountPaise, String reason) {
        if (isBlank(razorpayPaymentId)) {
            throw new IllegalArgumentException("Cannot refund without a captured Razorpay payment ID");
        }

        if (properties.hasLiveCredentials()) {
            try {
                RazorpayClient client = clientFactory.apply(
                        properties.effectiveKeyId(),
                        properties.effectiveKeySecret()
                );

                JSONObject refundRequest = new JSONObject();
                refundRequest.put("amount", amountPaise);
                if (reason != null && !reason.isBlank()) {
                    JSONObject notes = new JSONObject();
                    notes.put("reason", reason.trim());
                    refundRequest.put("notes", notes);
                }

                Refund refund = client.payments.refund(razorpayPaymentId.trim(), refundRequest);
                Object rawRefundId = refund.get("id");
                String refundId = String.valueOf(rawRefundId);
                return new GatewayRefund(refundId);
            } catch (RazorpayException ex) {
                log.error("Razorpay refund failed for paymentId={}: {}", razorpayPaymentId, ex.getMessage());
                throw new ServiceUnavailableException("Payment gateway refund is temporarily unavailable");
            }
        }

        if (properties.simulationEnabled()) {
            String simRefundId = "rfnd_sim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            log.info("Razorpay simulation mode active: generated local test refund for paymentId={}", razorpayPaymentId);
            return new GatewayRefund(simRefundId);
        }

        throw new ServiceUnavailableException(
                "Razorpay Test Mode credentials are not configured to process refunds"
        );
    }

    static String computeHmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 calculation failed", ex);
        }
    }

    private static boolean constantTimeHmacMatches(String payload, String secret, String providedHexSignature) {
        String expectedHex = computeHmacSha256Hex(payload, secret);
        byte[] expectedBytes = expectedHex.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = providedHexSignature.toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    private static RazorpayClient createSdkClient(String keyId, String keySecret) {
        try {
            return new RazorpayClient(keyId, keySecret);
        } catch (RazorpayException ex) {
            throw new ServiceUnavailableException("Failed to initialize Razorpay SDK client");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
