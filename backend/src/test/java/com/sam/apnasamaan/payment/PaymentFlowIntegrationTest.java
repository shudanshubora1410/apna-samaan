package com.sam.apnasamaan.payment;

import com.sam.apnasamaan.BaseIntegrationTest;
import com.sam.apnasamaan.auth.dto.AuthResponse;
import com.sam.apnasamaan.auth.dto.CustomerRegisterRequest;
import com.sam.apnasamaan.auth.dto.LoginRequest;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.common.dto.ApiErrorResponse;
import com.sam.apnasamaan.payment.config.RazorpayProperties;
import com.sam.apnasamaan.payment.dto.PaymentOrderCreateRequest;
import com.sam.apnasamaan.payment.dto.PaymentOrderResponse;
import com.sam.apnasamaan.payment.dto.PaymentRefundRequest;
import com.sam.apnasamaan.payment.dto.PaymentResponse;
import com.sam.apnasamaan.payment.dto.PaymentVerifyRequest;
import com.sam.apnasamaan.payment.dto.WebhookAckResponse;
import com.sam.apnasamaan.payment.entity.PaymentStatus;
import com.sam.apnasamaan.shipment.dto.ShipmentCancelRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentCreateRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentDetailResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentStatusHistoryResponse;
import com.sam.apnasamaan.shipment.entity.PackageCategory;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RazorpayProperties razorpayProperties;

    @Autowired
    private UserRepository userRepository;

    private final List<String> createdEmails = new ArrayList<>();

    @AfterEach
    void cleanUpTestUsers() {
        for (String email : createdEmails) {
            userRepository.findByEmailIgnoreCase(email).ifPresent(userRepository::delete);
        }
        createdEmails.clear();
    }

    @Test
    void checkoutVerify_advancesShipmentToSearchingDriver_andPrePickupCancelAutomaticallyRefunds() {
        String customerAEmail = registerCustomer("Rohan Pay", "9876510001");
        String customerAToken = login(customerAEmail, "Pass@12345");

        String customerBEmail = registerCustomer("Other User", "9876510002");
        String customerBToken = login(customerBEmail, "Pass@12345");

        ShipmentDetailResponse booked = bookShipment(customerAToken);
        Long shipmentId = booked.id();

        // 1. Customer B cannot create a payment order for Customer A's shipment (403 Forbidden)
        ResponseEntity<ApiErrorResponse> forbiddenOrder = restTemplate.exchange(
                "/api/v1/payments/orders",
                HttpMethod.POST,
                new HttpEntity<>(new PaymentOrderCreateRequest(shipmentId), bearerHeaders(customerBToken)),
                ApiErrorResponse.class
        );
        assertThat(forbiddenOrder.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 2. Customer A creates order twice -> idempotently reuses the same CREATED order & exact paise
        ResponseEntity<PaymentOrderResponse> orderRes1 = restTemplate.exchange(
                "/api/v1/payments/orders",
                HttpMethod.POST,
                new HttpEntity<>(new PaymentOrderCreateRequest(shipmentId), bearerHeaders(customerAToken)),
                PaymentOrderResponse.class
        );
        ResponseEntity<PaymentOrderResponse> orderRes2 = restTemplate.exchange(
                "/api/v1/payments/orders",
                HttpMethod.POST,
                new HttpEntity<>(new PaymentOrderCreateRequest(shipmentId), bearerHeaders(customerAToken)),
                PaymentOrderResponse.class
        );

        assertThat(orderRes1.getStatusCode()).isEqualTo(HttpStatus.OK);
        PaymentOrderResponse order = orderRes1.getBody();
        assertThat(order).isNotNull();
        assertThat(orderRes2.getBody()).isNotNull();
        assertThat(order.razorpayOrderId()).isEqualTo(orderRes2.getBody().razorpayOrderId());
        assertThat(order.amountInr()).isEqualByComparingTo(booked.totalFare());
        assertThat(order.amountPaise()).isEqualTo(booked.totalFare().multiply(new BigDecimal("100")).longValueExact());

        // 3. Forged signature on /verify is rejected with 400 and leaves shipment in PENDING_PAYMENT
        PaymentVerifyRequest forgedVerify = new PaymentVerifyRequest(
                shipmentId, order.razorpayOrderId(), "pay_sim_9001", "forged_bad_signature_hex"
        );
        ResponseEntity<ApiErrorResponse> badSigRes = restTemplate.exchange(
                "/api/v1/payments/verify",
                HttpMethod.POST,
                new HttpEntity<>(forgedVerify, bearerHeaders(customerAToken)),
                ApiErrorResponse.class
        );
        assertThat(badSigRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badSigRes.getBody().message()).contains("Invalid Razorpay payment signature");

        // 4. Authentic HMAC-SHA256 signature captures payment & transitions shipment -> SEARCHING_DRIVER
        String paymentId = "pay_sim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String validSig = hmacHex(
                order.razorpayOrderId() + "|" + paymentId,
                razorpayProperties.effectiveKeySecret()
        );
        PaymentVerifyRequest validVerify = new PaymentVerifyRequest(
                shipmentId, order.razorpayOrderId(), paymentId, validSig
        );

        ResponseEntity<PaymentResponse> verifyRes1 = restTemplate.exchange(
                "/api/v1/payments/verify",
                HttpMethod.POST,
                new HttpEntity<>(validVerify, bearerHeaders(customerAToken)),
                PaymentResponse.class
        );
        // Repeated call with same paymentId + signature is idempotent
        ResponseEntity<PaymentResponse> verifyRes2 = restTemplate.exchange(
                "/api/v1/payments/verify",
                HttpMethod.POST,
                new HttpEntity<>(validVerify, bearerHeaders(customerAToken)),
                PaymentResponse.class
        );

        assertThat(verifyRes1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verifyRes2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verifyRes1.getBody().status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(verifyRes1.getBody().shipmentStatus()).isEqualTo(ShipmentStatus.SEARCHING_DRIVER);

        // 5. Customer cancels paid SEARCHING_DRIVER shipment before pickup -> automatic full Razorpay refund!
        ResponseEntity<ShipmentDetailResponse> cancelRes = restTemplate.exchange(
                "/api/v1/shipments/" + shipmentId + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(
                        new ShipmentCancelRequest("Plans changed before driver assignment"),
                        bearerHeaders(customerAToken)
                ),
                ShipmentDetailResponse.class
        );
        assertThat(cancelRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelRes.getBody().status()).isEqualTo(ShipmentStatus.CANCELLED);

        ResponseEntity<PaymentResponse> paymentStatusRes = restTemplate.exchange(
                "/api/v1/payments/shipment/" + shipmentId,
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(customerAToken)),
                PaymentResponse.class
        );
        assertThat(paymentStatusRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paymentStatusRes.getBody().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(paymentStatusRes.getBody().razorpayRefundId()).startsWith("rfnd_sim_");
        assertThat(paymentStatusRes.getBody().refundedAmountInr()).isEqualByComparingTo(booked.totalFare());

        ResponseEntity<List<ShipmentStatusHistoryResponse>> historyRes = restTemplate.exchange(
                "/api/v1/shipments/" + shipmentId + "/history",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(customerAToken)),
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(historyRes.getBody()).hasSize(3); // PENDING_PAYMENT -> SEARCHING_DRIVER -> CANCELLED
        assertThat(historyRes.getBody().get(2).note()).contains("Full refund initiated");
    }

    @Test
    void signedWebhooks_handleFailedRetryCaptureIdempotentReplay_andAdminSearchAndRefund() {
        String customerEmail = registerCustomer("Priya Webhook", "9876510003");
        String customerToken = login(customerEmail, "Pass@12345");
        String adminToken = login(TEST_ADMIN_EMAIL, TEST_ADMIN_PASSWORD);

        ShipmentDetailResponse booked = bookShipment(customerToken);
        Long shipmentId = booked.id();

        PaymentOrderResponse order1 = restTemplate.exchange(
                "/api/v1/payments/orders",
                HttpMethod.POST,
                new HttpEntity<>(new PaymentOrderCreateRequest(shipmentId), bearerHeaders(customerToken)),
                PaymentOrderResponse.class
        ).getBody();
        assertThat(order1).isNotNull();

        // 1. Unsigned webhook is rejected with 400 Bad Request
        String failedPayload = """
                {
                  "event": "payment.failed",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_fail_001",
                        "order_id": "%s",
                        "error_description": "Card declined by issuing bank"
                      }
                    }
                  }
                }
                """.formatted(order1.razorpayOrderId());

        ResponseEntity<ApiErrorResponse> unsignedWebhook = restTemplate.postForEntity(
                "/api/v1/payments/webhook",
                new HttpEntity<>(failedPayload, jsonHeaders(null, "evt_unsigned_1")),
                ApiErrorResponse.class
        );
        assertThat(unsignedWebhook.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 2. Signed payment.failed webhook marks order1 FAILED while keeping shipment PENDING_PAYMENT for retry
        String failedSig = hmacHex(failedPayload, razorpayProperties.effectiveWebhookSecret());
        ResponseEntity<WebhookAckResponse> failAck = restTemplate.postForEntity(
                "/api/v1/payments/webhook",
                new HttpEntity<>(failedPayload, jsonHeaders(failedSig, "evt_fail_" + UUID.randomUUID())),
                WebhookAckResponse.class
        );
        assertThat(failAck.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failAck.getBody().status()).isEqualTo("PROCESSED");

        // 3. Customer retries -> fresh order2 is created since order1 FAILED
        PaymentOrderResponse order2 = restTemplate.exchange(
                "/api/v1/payments/orders",
                HttpMethod.POST,
                new HttpEntity<>(new PaymentOrderCreateRequest(shipmentId), bearerHeaders(customerToken)),
                PaymentOrderResponse.class
        ).getBody();
        assertThat(order2).isNotNull();
        assertThat(order2.razorpayOrderId()).isNotEqualTo(order1.razorpayOrderId());

        // 4. Signed payment.captured webhook captures order2 and transitions shipment -> SEARCHING_DRIVER
        String capturedPaymentId = "pay_wh_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String capturedPayload = """
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "order_id": "%s",
                        "amount": %d,
                        "status": "captured"
                      }
                    }
                  }
                }
                """.formatted(capturedPaymentId, order2.razorpayOrderId(), order2.amountPaise());

        String capturedSig = hmacHex(capturedPayload, razorpayProperties.effectiveWebhookSecret());
        String eventId = "evt_cap_" + UUID.randomUUID();

        ResponseEntity<WebhookAckResponse> capAck1 = restTemplate.postForEntity(
                "/api/v1/payments/webhook",
                new HttpEntity<>(capturedPayload, jsonHeaders(capturedSig, eventId)),
                WebhookAckResponse.class
        );
        assertThat(capAck1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capAck1.getBody().status()).isEqualTo("PROCESSED");

        // 5. Replaying the exact same webhook event is 100% idempotent
        ResponseEntity<WebhookAckResponse> capAck2 = restTemplate.postForEntity(
                "/api/v1/payments/webhook",
                new HttpEntity<>(capturedPayload, jsonHeaders(capturedSig, eventId)),
                WebhookAckResponse.class
        );
        assertThat(capAck2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capAck2.getBody().status()).isEqualTo("ALREADY_PROCESSED");

        // 6. Admin searches CAPTURED payment & issues operational refund (idempotent on repeat)
        ResponseEntity<Map<String, Object>> adminSearch = restTemplate.exchange(
                "/api/v1/admin/payments?status=CAPTURED&search=" + booked.trackingCode(),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(adminToken)),
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(adminSearch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminSearch.getBody()).containsEntry("totalElements", 1);

        ResponseEntity<PaymentResponse> adminRefund1 = restTemplate.exchange(
                "/api/v1/admin/payments/" + order2.paymentId() + "/refund",
                HttpMethod.POST,
                new HttpEntity<>(new PaymentRefundRequest("Operational admin refund check"), bearerHeaders(adminToken)),
                PaymentResponse.class
        );
        ResponseEntity<PaymentResponse> adminRefund2 = restTemplate.exchange(
                "/api/v1/admin/payments/" + order2.paymentId() + "/refund",
                HttpMethod.POST,
                new HttpEntity<>(new PaymentRefundRequest("Operational admin refund check"), bearerHeaders(adminToken)),
                PaymentResponse.class
        );

        assertThat(adminRefund1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminRefund1.getBody().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(adminRefund2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminRefund2.getBody().razorpayRefundId()).isEqualTo(adminRefund1.getBody().razorpayRefundId());
    }

    private ShipmentDetailResponse bookShipment(String customerToken) {
        ShipmentCreateRequest req = new ShipmentCreateRequest(
                "Block A, Connaught Place, New Delhi 110001",
                "Delhi",
                28.6315,
                77.2167,
                "Rohan Sharma",
                "9876543210",
                "DLF Cyber Hub, Phase 2, Gurugram 122002",
                "Gurugram",
                28.4950,
                77.0895,
                "Priya Verma",
                "9812345678",
                "Washing machine and 3 packed cartons",
                PackageCategory.HOUSEHOLD,
                new BigDecimal("120.00"),
                110,
                80,
                90,
                true,
                "CARGO_AUTO",
                true
        );

        ResponseEntity<ShipmentDetailResponse> res = restTemplate.exchange(
                "/api/v1/shipments",
                HttpMethod.POST,
                new HttpEntity<>(req, bearerHeaders(customerToken)),
                ShipmentDetailResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private String registerCustomer(String name, String phone) {
        String email = "paycust." + UUID.randomUUID().toString().substring(0, 8) + "@apnasamaan.test";
        createdEmails.add(email);
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity(
                "/api/v1/auth/register/customer",
                new CustomerRegisterRequest(name, email, phone, "Pass@12345"),
                AuthResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return email;
    }

    private String login(String email, String password) {
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, password),
                AuthResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody().accessToken();
    }

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static HttpHeaders jsonHeaders(String razorpaySig, String eventId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (razorpaySig != null) {
            headers.set("X-Razorpay-Signature", razorpaySig);
        }
        if (eventId != null) {
            headers.set("X-Razorpay-Event-Id", eventId);
        }
        return headers;
    }

    private static String hmacHex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
