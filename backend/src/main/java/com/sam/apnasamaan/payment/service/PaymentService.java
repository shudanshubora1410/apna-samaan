package com.sam.apnasamaan.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.apnasamaan.auth.entity.Role;
import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.auth.security.UserPrincipal;
import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.common.exception.ForbiddenOperationException;
import com.sam.apnasamaan.common.exception.ResourceNotFoundException;
import com.sam.apnasamaan.common.util.TokenHashUtil;
import com.sam.apnasamaan.payment.config.RazorpayProperties;
import com.sam.apnasamaan.payment.dto.PaymentOrderCreateRequest;
import com.sam.apnasamaan.payment.dto.PaymentOrderResponse;
import com.sam.apnasamaan.payment.dto.PaymentResponse;
import com.sam.apnasamaan.payment.dto.PaymentSimulateRequest;
import com.sam.apnasamaan.payment.dto.PaymentVerifyRequest;
import com.sam.apnasamaan.payment.dto.WebhookAckResponse;
import com.sam.apnasamaan.payment.entity.Payment;
import com.sam.apnasamaan.payment.entity.PaymentStatus;
import com.sam.apnasamaan.payment.entity.PaymentWebhookEvent;
import com.sam.apnasamaan.payment.repository.PaymentRepository;
import com.sam.apnasamaan.payment.repository.PaymentWebhookEventRepository;
import com.sam.apnasamaan.shipment.entity.Shipment;
import com.sam.apnasamaan.shipment.entity.ShipmentActorRole;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import com.sam.apnasamaan.shipment.event.ShipmentReadyForMatchingEvent;
import com.sam.apnasamaan.shipment.repository.ShipmentRepository;
import com.sam.apnasamaan.shipment.service.ShipmentTransitionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class PaymentService {

    private static final String CURRENCY_INR = "INR";
    private static final BigDecimal PAISE_PER_RUPEE = new BigDecimal("100");

    private final PaymentRepository paymentRepository;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentTransitionService transitionService;
    private final PaymentGateway paymentGateway;
    private final RazorpayProperties razorpayProperties;
    private final PaymentMapper paymentMapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentWebhookEventRepository webhookEventRepository,
            ShipmentRepository shipmentRepository,
            ShipmentTransitionService transitionService,
            PaymentGateway paymentGateway,
            RazorpayProperties razorpayProperties,
            PaymentMapper paymentMapper,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        this.paymentRepository = paymentRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.shipmentRepository = shipmentRepository;
        this.transitionService = transitionService;
        this.paymentGateway = paymentGateway;
        this.razorpayProperties = razorpayProperties;
        this.paymentMapper = paymentMapper;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Creates a Razorpay Test Mode order from the server-computed shipment fare,
     * or idempotently returns the active CREATED order if one already exists.
     */
    @Transactional
    public PaymentOrderResponse createOrReuseOrder(Long customerUserId, PaymentOrderCreateRequest request) {
        Shipment shipment = shipmentRepository.findByIdWithDetails(request.shipmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found with id: " + request.shipmentId()));

        if (!shipment.getCustomer().getId().equals(customerUserId)) {
            throw new ForbiddenOperationException("You can only initiate payment for your own shipment");
        }
        if (shipment.getStatus() != ShipmentStatus.PENDING_PAYMENT) {
            throw new BadRequestException(
                    "Payment order can only be created when shipment is in PENDING_PAYMENT (current: "
                            + shipment.getStatus() + ")"
            );
        }

        BigDecimal amountInr = shipment.getTotalFare().setScale(2, RoundingMode.HALF_UP);
        long amountPaise = toPaise(amountInr);

        // Reuse an existing open CREATED order if the customer refreshes Checkout
        List<Payment> existingPayments = paymentRepository.findByShipmentIdOrderByCreatedAtDesc(shipment.getId());
        for (Payment existing : existingPayments) {
            if (existing.getStatus() == PaymentStatus.CAPTURED) {
                throw new BadRequestException("Shipment payment has already been captured");
            }
            if (existing.getStatus() == PaymentStatus.CREATED
                    && existing.getAmountPaise() == amountPaise
                    && !razorpayProperties.effectiveKeyId().isBlank()) {
                return paymentMapper.toOrderResponse(existing, razorpayProperties.effectiveKeyId());
            }
        }

        long attemptNumber = existingPayments.size() + 1L;
        String receipt = buildReceipt(shipment.getTrackingCode(), attemptNumber);

        PaymentGateway.GatewayOrder gatewayOrder = paymentGateway.createOrder(
                amountPaise,
                CURRENCY_INR,
                receipt,
                Map.of(
                        "shipmentId", String.valueOf(shipment.getId()),
                        "trackingCode", shipment.getTrackingCode()
                )
        );

        Payment created = Payment.builder()
                .shipment(shipment)
                .customer(shipment.getCustomer())
                .razorpayOrderId(gatewayOrder.razorpayOrderId())
                .amountInr(amountInr)
                .amountPaise(amountPaise)
                .currency(CURRENCY_INR)
                .receipt(receipt)
                .status(PaymentStatus.CREATED)
                .build();

        Payment saved = paymentRepository.save(created);
        log.info("Created Razorpay order: paymentId={}, shipmentId={}, orderId={}, amountPaise={}",
                saved.getId(), shipment.getId(), saved.getRazorpayOrderId(), amountPaise);

        return paymentMapper.toOrderResponse(saved, gatewayOrder.publicKeyId());
    }

    /**
     * Verifies Razorpay Checkout's client callback signature via HMAC-SHA256 and transitions
     * the shipment from PENDING_PAYMENT -> SEARCHING_DRIVER idempotently.
     */
    @Transactional
    public PaymentResponse verifyCheckoutPayment(Long customerUserId, PaymentVerifyRequest request) {
        Payment payment = paymentRepository.findByRazorpayOrderIdWithDetails(request.razorpayOrderId().trim())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Payment order not found: " + request.razorpayOrderId()
                ));

        Shipment shipment = payment.getShipment();
        if (!shipment.getId().equals(request.shipmentId())) {
            throw new BadRequestException("Payment order does not belong to shipment id: " + request.shipmentId());
        }
        if (!shipment.getCustomer().getId().equals(customerUserId)) {
            throw new ForbiddenOperationException("You can only verify payment for your own shipment");
        }

        String paymentId = request.razorpayPaymentId().trim();
        String signature = request.razorpaySignature().trim();

        // Always verify cryptographic HMAC before returning, even on repeated idempotent calls
        if (!paymentGateway.verifyCheckoutSignature(payment.getRazorpayOrderId(), paymentId, signature)) {
            throw new BadRequestException("Invalid Razorpay payment signature");
        }

        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            if (paymentId.equals(payment.getRazorpayPaymentId())) {
                return paymentMapper.toResponse(payment);
            }
            throw new BadRequestException("Order was already captured under a different payment ID");
        }
        if (payment.getStatus() == PaymentStatus.REFUNDED || payment.getStatus() == PaymentStatus.CANCELLED) {
            throw new BadRequestException("Cannot capture payment in status " + payment.getStatus());
        }

        markCapturedAndAdvanceShipment(
                payment,
                paymentId,
                signature,
                shipment.getCustomer(),
                ShipmentActorRole.CUSTOMER,
                "Payment verified via Razorpay Checkout (" + paymentId + ")"
        );

        return paymentMapper.toResponse(payment);
    }

    /**
     * Completes a simulated test payment when RAZORPAY_SIMULATION_ENABLED=true without exposing
     * any secret to the browser. Strictly blocked (403) when live Razorpay credentials are configured.
     */
    @Transactional
    public PaymentResponse simulateCheckoutPayment(Long customerUserId, PaymentSimulateRequest request) {
        if (!razorpayProperties.simulationEnabled() || razorpayProperties.hasLiveCredentials()) {
            throw new ForbiddenOperationException(
                    "Local checkout simulation is disabled when live Razorpay credentials are active"
            );
        }

        String orderId = request.razorpayOrderId().trim();
        if (!orderId.startsWith("order_sim_")) {
            throw new BadRequestException("Only simulated orders (order_sim_*) can use simulation checkout");
        }

        Payment payment = paymentRepository.findByRazorpayOrderIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment order not found: " + orderId));

        Shipment shipment = payment.getShipment();
        if (!shipment.getId().equals(request.shipmentId())) {
            throw new BadRequestException("Payment order does not belong to shipment id: " + request.shipmentId());
        }
        if (!shipment.getCustomer().getId().equals(customerUserId)) {
            throw new ForbiddenOperationException("You can only complete payment for your own shipment");
        }
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return paymentMapper.toResponse(payment);
        }
        if (payment.getStatus() == PaymentStatus.REFUNDED || payment.getStatus() == PaymentStatus.CANCELLED) {
            throw new BadRequestException("Cannot capture payment in status " + payment.getStatus());
        }

        String simPaymentId = "pay_sim_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        markCapturedAndAdvanceShipment(
                payment,
                simPaymentId,
                "sim_verified_local",
                shipment.getCustomer(),
                ShipmentActorRole.CUSTOMER,
                "Payment verified via Razorpay Test Simulation (" + simPaymentId + ")"
        );
        return paymentMapper.toResponse(payment);
    }

    /**
     * Processes signed Razorpay server-to-server webhooks idempotently.
     */
    @Transactional
    public WebhookAckResponse handleWebhook(String rawPayload, String signatureHeader, String eventIdHeader) {
        if (rawPayload == null || rawPayload.isBlank()) {
            throw new BadRequestException("Webhook payload cannot be empty");
        }
        if (!paymentGateway.verifyWebhookSignature(rawPayload, signatureHeader)) {
            throw new BadRequestException("Invalid Razorpay webhook signature");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawPayload);
        } catch (Exception ex) {
            throw new BadRequestException("Malformed webhook JSON payload");
        }

        String eventType = textOrNull(root.get("event"));
        if (eventType == null || eventType.isBlank()) {
            throw new BadRequestException("Webhook JSON is missing 'event' field");
        }

        // Prefer Razorpay's unique X-Razorpay-Event-Id header; fall back to SHA-256 of raw payload
        String eventKey = (eventIdHeader != null && !eventIdHeader.isBlank())
                ? eventIdHeader.trim()
                : TokenHashUtil.sha256Hex(rawPayload);

        if (webhookEventRepository.existsByEventKey(eventKey)) {
            log.info("Ignoring duplicate Razorpay webhook delivery: eventType={}, eventKey={}", eventType, eventKey);
            return new WebhookAckResponse("ALREADY_PROCESSED", eventType, "Webhook event already processed");
        }

        JsonNode paymentEntity = root.path("payload").path("payment").path("entity");
        JsonNode orderEntity = root.path("payload").path("order").path("entity");
        JsonNode refundEntity = root.path("payload").path("refund").path("entity");

        String orderId = firstNonBlank(
                textOrNull(paymentEntity.get("order_id")),
                textOrNull(orderEntity.get("id"))
        );
        String paymentId = firstNonBlank(
                textOrNull(paymentEntity.get("id")),
                textOrNull(refundEntity.get("payment_id"))
        );

        switch (eventType) {
            case "payment.captured", "order.paid" -> handleWebhookCapture(orderId, paymentId, paymentEntity);
            case "payment.failed" -> handleWebhookFailure(orderId, paymentEntity);
            case "refund.processed", "refund.created" -> handleWebhookRefund(paymentId, refundEntity);
            default -> log.info("Acknowledged unhandled Razorpay webhook eventType={}", eventType);
        }

        webhookEventRepository.save(PaymentWebhookEvent.builder()
                .eventKey(eventKey)
                .eventType(eventType)
                .razorpayOrderId(orderId)
                .razorpayPaymentId(paymentId)
                .build());

        return new WebhookAckResponse("PROCESSED", eventType, "Webhook processed successfully");
    }

    @Transactional(readOnly = true)
    public PaymentResponse getLatestPaymentForShipment(Long shipmentId, UserPrincipal requester) {
        Shipment shipment = shipmentRepository.findByIdWithDetails(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found with id: " + shipmentId));

        if (requester.getRole() != Role.ADMIN
                && !(requester.getRole() == Role.CUSTOMER && shipment.getCustomer().getId().equals(requester.getId()))) {
            throw new ForbiddenOperationException("You do not have permission to view payment details for this shipment");
        }

        List<Payment> payments = paymentRepository.findByShipmentIdOrderByCreatedAtDesc(shipmentId);
        if (payments.isEmpty()) {
            throw new ResourceNotFoundException("No payment record exists yet for shipment id: " + shipmentId);
        }
        return paymentMapper.toResponse(payments.get(0));
    }

    /**
     * Invoked automatically when a shipment is cancelled before PICKED_UP or times out in Phase 5:
     * cancels any unpaid CREATED orders and issues a full Razorpay refund if already CAPTURED.
     */
    @Transactional
    public Optional<Payment> cancelOrRefundForShipment(Shipment shipment, String reason) {
        List<Payment> payments = paymentRepository.findByShipmentIdOrderByCreatedAtDesc(shipment.getId());
        Optional<Payment> refunded = Optional.empty();

        for (Payment p : payments) {
            if (p.getStatus() == PaymentStatus.CREATED) {
                p.setStatus(PaymentStatus.CANCELLED);
                p.setFailureReason("Shipment cancelled before payment completion");
                paymentRepository.save(p);
            } else if (p.getStatus() == PaymentStatus.CAPTURED) {
                refundPaymentInternal(p, reason);
                refunded = Optional.of(p);
            }
        }
        return refunded;
    }

    @Transactional
    public PaymentResponse refundPaymentByAdmin(Long paymentId, String reason) {
        Payment payment = paymentRepository.findByIdWithDetails(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return paymentMapper.toResponse(payment);
        }
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new BadRequestException(
                    "Only CAPTURED payments can be refunded (current status: " + payment.getStatus() + ")"
            );
        }

        refundPaymentInternal(payment, reason);
        return paymentMapper.toResponse(payment);
    }

    private void handleWebhookCapture(String orderId, String paymentId, JsonNode paymentEntity) {
        if (orderId == null || paymentId == null) {
            log.warn("Webhook capture event missing order_id or payment_id");
            return;
        }
        paymentRepository.findByRazorpayOrderIdWithDetails(orderId).ifPresent(payment -> {
            // Verify captured amount matches server-expected paise when present in webhook payload
            JsonNode amountNode = paymentEntity.get("amount");
            if (amountNode != null && amountNode.canConvertToLong()
                    && amountNode.asLong() != payment.getAmountPaise()) {
                log.error("Webhook amount mismatch for orderId={}: expected={} paise, got={} paise",
                        orderId, payment.getAmountPaise(), amountNode.asLong());
                throw new BadRequestException("Webhook payment amount does not match shipment order amount");
            }

            if (payment.getStatus() == PaymentStatus.CAPTURED || payment.getStatus() == PaymentStatus.REFUNDED) {
                return;
            }

            markCapturedAndAdvanceShipment(
                    payment,
                    paymentId,
                    payment.getRazorpaySignature(),
                    null,
                    ShipmentActorRole.SYSTEM,
                    "Payment confirmed via Razorpay webhook (" + paymentId + ")"
            );
        });
    }

    private void handleWebhookFailure(String orderId, JsonNode paymentEntity) {
        if (orderId == null) {
            return;
        }
        paymentRepository.findByRazorpayOrderIdWithDetails(orderId).ifPresent(payment -> {
            // Never downgrade an already captured or refunded payment on out-of-order failure webhook
            if (payment.getStatus() == PaymentStatus.CREATED) {
                String errorDesc = firstNonBlank(
                        textOrNull(paymentEntity.get("error_description")),
                        "Payment failed at Razorpay gateway"
                );
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason(truncate(errorDesc, 300));
                paymentRepository.save(payment);
                log.info("Marked payment order FAILED via webhook: orderId={}", orderId);
            }
        });
    }

    private void handleWebhookRefund(String paymentId, JsonNode refundEntity) {
        if (paymentId == null) {
            return;
        }
        paymentRepository.findByRazorpayPaymentIdWithDetails(paymentId).ifPresent(payment -> {
            if (payment.getStatus() != PaymentStatus.REFUNDED) {
                String refundId = textOrNull(refundEntity.get("id"));
                payment.setStatus(PaymentStatus.REFUNDED);
                if (refundId != null && !refundId.isBlank()) {
                    payment.setRazorpayRefundId(refundId);
                }
                payment.setRefundedAmountInr(payment.getAmountInr());
                if (payment.getRefundReason() == null) {
                    payment.setRefundReason("Refund confirmed via Razorpay webhook");
                }
                payment.setRefundedAt(Instant.now());
                paymentRepository.save(payment);
            }
        });
    }

    private void markCapturedAndAdvanceShipment(
            Payment payment,
            String razorpayPaymentId,
            String razorpaySignature,
            User actor,
            ShipmentActorRole actorRole,
            String historyNote
    ) {
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setRazorpayPaymentId(razorpayPaymentId);
        if (razorpaySignature != null && !razorpaySignature.isBlank()) {
            payment.setRazorpaySignature(razorpaySignature);
        }
        payment.setFailureReason(null);
        payment.setCapturedAt(Instant.now());
        paymentRepository.save(payment);

        Shipment shipment = payment.getShipment();
        if (shipment.getStatus() == ShipmentStatus.PENDING_PAYMENT) {
            shipment.setSearchStartedAt(Instant.now());
            transitionService.transition(
                    shipment,
                    ShipmentStatus.SEARCHING_DRIVER,
                    actor,
                    actorRole,
                    historyNote
            );
            shipmentRepository.save(shipment);
            log.info("Shipment transitioned PENDING_PAYMENT -> SEARCHING_DRIVER: shipmentId={}, paymentId={}",
                    shipment.getId(), razorpayPaymentId);

            // Immediately dispatch the first 60-second Haversine-nearest driver offer
            eventPublisher.publishEvent(new ShipmentReadyForMatchingEvent(shipment.getId()));
        }
    }

    private void refundPaymentInternal(Payment payment, String reason) {
        String cleanReason = truncate(reason != null ? reason.trim() : "Shipment refund", 300);
        PaymentGateway.GatewayRefund refund = paymentGateway.refundFull(
                payment.getRazorpayPaymentId(),
                payment.getAmountPaise(),
                cleanReason
        );

        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRazorpayRefundId(refund.razorpayRefundId());
        payment.setRefundedAmountInr(payment.getAmountInr());
        payment.setRefundReason(cleanReason);
        payment.setRefundedAt(Instant.now());
        paymentRepository.save(payment);

        log.info("Processed full Razorpay refund: paymentId={}, shipmentId={}, refundId={}, amountInr={}",
                payment.getId(), payment.getShipment().getId(), refund.razorpayRefundId(), payment.getAmountInr());
    }

    static long toPaise(BigDecimal amountInr) {
        return amountInr.multiply(PAISE_PER_RUPEE)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private static String buildReceipt(String trackingCode, long attempt) {
        String candidate = attempt <= 1 ? trackingCode : trackingCode + "-" + attempt;
        return candidate.length() <= 40 ? candidate : candidate.substring(0, 40);
    }

    private static String textOrNull(JsonNode node) {
        return (node != null && !node.isNull() && node.isValueNode()) ? node.asText() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
