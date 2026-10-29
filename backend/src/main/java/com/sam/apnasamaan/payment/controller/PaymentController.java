package com.sam.apnasamaan.payment.controller;

import com.sam.apnasamaan.auth.security.UserPrincipal;
import com.sam.apnasamaan.payment.dto.PaymentOrderCreateRequest;
import com.sam.apnasamaan.payment.dto.PaymentOrderResponse;
import com.sam.apnasamaan.payment.dto.PaymentResponse;
import com.sam.apnasamaan.payment.dto.PaymentSimulateRequest;
import com.sam.apnasamaan.payment.dto.PaymentVerifyRequest;
import com.sam.apnasamaan.payment.dto.WebhookAckResponse;
import com.sam.apnasamaan.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Razorpay Test Mode order creation, HMAC verification, and idempotent webhooks")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/orders")
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create or reuse a Razorpay Test Mode order for a PENDING_PAYMENT shipment")
    public ResponseEntity<PaymentOrderResponse> createOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody PaymentOrderCreateRequest request
    ) {
        return ResponseEntity.ok(paymentService.createOrReuseOrder(principal.getId(), request));
    }

    @PostMapping("/verify")
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Verify Razorpay Checkout HMAC signature and advance shipment to SEARCHING_DRIVER")
    public ResponseEntity<PaymentResponse> verifyPayment(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody PaymentVerifyRequest request
    ) {
        return ResponseEntity.ok(paymentService.verifyCheckoutPayment(principal.getId(), request));
    }

    @PostMapping("/simulate-checkout")
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Complete a local simulated Razorpay Test payment (only when RAZORPAY_SIMULATION_ENABLED=true)")
    public ResponseEntity<PaymentResponse> simulateCheckout(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody PaymentSimulateRequest request
    ) {
        return ResponseEntity.ok(paymentService.simulateCheckoutPayment(principal.getId(), request));
    }

    @PostMapping("/webhook")
    @Operation(summary = "Idempotent server-to-server Razorpay webhook verified via X-Razorpay-Signature")
    public ResponseEntity<WebhookAckResponse> handleWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(name = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(name = "X-Razorpay-Event-Id", required = false) String eventId
    ) {
        return ResponseEntity.ok(paymentService.handleWebhook(rawPayload, signature, eventId));
    }

    @GetMapping("/shipment/{shipmentId}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get the latest payment and refund status for a shipment")
    public ResponseEntity<PaymentResponse> getPaymentForShipment(
            @PathVariable Long shipmentId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(paymentService.getLatestPaymentForShipment(shipmentId, principal));
    }
}
