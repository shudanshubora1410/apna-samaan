package com.sam.apnasamaan.payment.service;

import com.razorpay.Order;
import com.razorpay.OrderClient;
import com.razorpay.PaymentClient;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Refund;
import com.sam.apnasamaan.common.exception.ServiceUnavailableException;
import com.sam.apnasamaan.payment.config.RazorpayProperties;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RazorpayPaymentGatewayTest {

    @Test
    void unconfiguredStrictMode_throws503AndRejectsSignatures() {
        RazorpayProperties unconfigured = new RazorpayProperties(
                "rzp_test_CHANGE_ME",
                "CHANGE_ME_RAZORPAY_KEY_SECRET",
                "CHANGE_ME_RAZORPAY_WEBHOOK_SECRET",
                false
        );
        RazorpayPaymentGateway gateway = new RazorpayPaymentGateway(unconfigured);

        assertThatThrownBy(() -> gateway.createOrder(71536L, "INR", "AS-20261025-TEST01", Map.of()))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("not configured");

        assertThatThrownBy(() -> gateway.refundFull("pay_123", 71536L, "Timeout"))
                .isInstanceOf(ServiceUnavailableException.class);

        assertThat(gateway.verifyCheckoutSignature("order_1", "pay_1", "deadbeef")).isFalse();
        assertThat(gateway.verifyWebhookSignature("{\"event\":\"payment.captured\"}", "deadbeef")).isFalse();
    }

    @Test
    void liveSdkCredentials_delegateOrderCreationAndRefundToRazorpayClient() throws Exception {
        RazorpayProperties liveTestProps = new RazorpayProperties(
                "rzp_test_liveSdkKey123",
                "live_sdk_secret_key_456",
                "live_webhook_secret_789",
                false
        );

        RazorpayClient mockClient = mock(RazorpayClient.class);
        OrderClient mockOrders = mock(OrderClient.class);
        PaymentClient mockPayments = mock(PaymentClient.class);
        setPublicField(mockClient, "orders", mockOrders);
        setPublicField(mockClient, "payments", mockPayments);

        Order fakeOrder = new Order(new JSONObject().put("id", "order_LiveTest999"));
        when(mockOrders.create(any(JSONObject.class))).thenReturn(fakeOrder);

        Refund fakeRefund = new Refund(new JSONObject().put("id", "rfnd_LiveTest888"));
        when(mockPayments.refund(eq("pay_LiveTest777"), any(JSONObject.class))).thenReturn(fakeRefund);

        RazorpayPaymentGateway gateway = new RazorpayPaymentGateway(liveTestProps, (k, s) -> mockClient);

        PaymentGateway.GatewayOrder created = gateway.createOrder(
                71536L, "INR", "AS-20261025-KKD3YQ", Map.of("shipmentId", "42")
        );
        assertThat(created.razorpayOrderId()).isEqualTo("order_LiveTest999");
        assertThat(created.publicKeyId()).isEqualTo("rzp_test_liveSdkKey123");

        ArgumentCaptor<JSONObject> orderCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(mockOrders).create(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getLong("amount")).isEqualTo(71536L);
        assertThat(orderCaptor.getValue().getString("currency")).isEqualTo("INR");

        PaymentGateway.GatewayRefund refund = gateway.refundFull("pay_LiveTest777", 71536L, "No driver available");
        assertThat(refund.razorpayRefundId()).isEqualTo("rfnd_LiveTest888");

        // SDK failure translates cleanly to 503 ServiceUnavailableException
        when(mockOrders.create(any(JSONObject.class))).thenThrow(new RazorpayException("Gateway timeout"));
        assertThatThrownBy(() -> gateway.createOrder(50000L, "INR", "AS-ERR", Map.of()))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("temporarily unavailable");
    }

    @Test
    void hmacSignatureVerification_acceptsAuthenticSignatures_andRejectsTampering() {
        String keySecret = "test_checkout_secret_key";
        String webhookSecret = "test_webhook_secret_key";
        RazorpayProperties props = new RazorpayProperties(
                "rzp_test_abc123",
                keySecret,
                webhookSecret,
                false
        );
        RazorpayPaymentGateway gateway = new RazorpayPaymentGateway(props);

        String orderId = "order_Test1001";
        String paymentId = "pay_Test2002";
        String validCheckoutSig = RazorpayPaymentGateway.computeHmacSha256Hex(orderId + "|" + paymentId, keySecret);

        assertThat(gateway.verifyCheckoutSignature(orderId, paymentId, validCheckoutSig)).isTrue();
        assertThat(gateway.verifyCheckoutSignature(orderId, "pay_TAMPERED", validCheckoutSig)).isFalse();
        assertThat(gateway.verifyCheckoutSignature(orderId, paymentId, "0000" + validCheckoutSig.substring(4))).isFalse();

        String rawWebhook = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\"}}}}";
        String validWebhookSig = RazorpayPaymentGateway.computeHmacSha256Hex(rawWebhook, webhookSecret);

        assertThat(gateway.verifyWebhookSignature(rawWebhook, validWebhookSig)).isTrue();
        assertThat(gateway.verifyWebhookSignature(rawWebhook + " ", validWebhookSig)).isFalse();
        assertThat(gateway.verifyWebhookSignature(rawWebhook, null)).isFalse();
    }

    private static void setPublicField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getField(fieldName);
        field.set(target, value);
    }
}
