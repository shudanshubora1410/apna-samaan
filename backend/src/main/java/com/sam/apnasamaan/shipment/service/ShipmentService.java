package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.auth.entity.Role;
import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.auth.security.UserPrincipal;
import com.sam.apnasamaan.common.dto.PagedResponse;
import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.common.exception.ForbiddenOperationException;
import com.sam.apnasamaan.common.exception.ResourceNotFoundException;
import com.sam.apnasamaan.driver.entity.DriverProfile;
import com.sam.apnasamaan.driver.repository.DriverProfileRepository;
import com.sam.apnasamaan.payment.entity.Payment;
import com.sam.apnasamaan.payment.service.PaymentService;
import com.sam.apnasamaan.shipment.dto.ShipmentCancelRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentCreateRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentDetailResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentQuoteRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentQuoteResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentStatusHistoryResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentSummaryResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentTrackingResponse;
import com.sam.apnasamaan.shipment.dto.VehicleOptionQuoteResponse;
import com.sam.apnasamaan.shipment.entity.DriverJobOffer;
import com.sam.apnasamaan.shipment.entity.DriverJobOfferStatus;
import com.sam.apnasamaan.shipment.entity.PackageCategory;
import com.sam.apnasamaan.shipment.entity.Shipment;
import com.sam.apnasamaan.shipment.entity.ShipmentActorRole;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import com.sam.apnasamaan.shipment.entity.VehicleType;
import com.sam.apnasamaan.shipment.repository.DriverJobOfferRepository;
import com.sam.apnasamaan.shipment.repository.ShipmentRepository;
import com.sam.apnasamaan.shipment.repository.ShipmentStatusHistoryRepository;
import com.sam.apnasamaan.shipment.repository.VehicleTypeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class ShipmentService {

    private static final String CURRENCY_INR = "INR";
    private static final String TRACKING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter TRACKING_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MAX_PAGE_SIZE = 50;

    private final ShipmentRepository shipmentRepository;
    private final ShipmentStatusHistoryRepository historyRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final DriverJobOfferRepository offerRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final UserRepository userRepository;
    private final RoutingAndGeocodingService routingService;
    private final VehicleSuggestionService suggestionService;
    private final PricingService pricingService;
    private final ShipmentStateMachine stateMachine;
    private final ShipmentTransitionService transitionService;
    private final PaymentService paymentService;
    private final ShipmentMapper shipmentMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public ShipmentService(
            ShipmentRepository shipmentRepository,
            ShipmentStatusHistoryRepository historyRepository,
            VehicleTypeRepository vehicleTypeRepository,
            DriverJobOfferRepository offerRepository,
            DriverProfileRepository driverProfileRepository,
            UserRepository userRepository,
            RoutingAndGeocodingService routingService,
            VehicleSuggestionService suggestionService,
            PricingService pricingService,
            ShipmentStateMachine stateMachine,
            ShipmentTransitionService transitionService,
            PaymentService paymentService,
            ShipmentMapper shipmentMapper
    ) {
        this.shipmentRepository = shipmentRepository;
        this.historyRepository = historyRepository;
        this.vehicleTypeRepository = vehicleTypeRepository;
        this.offerRepository = offerRepository;
        this.driverProfileRepository = driverProfileRepository;
        this.userRepository = userRepository;
        this.routingService = routingService;
        this.suggestionService = suggestionService;
        this.pricingService = pricingService;
        this.stateMachine = stateMachine;
        this.transitionService = transitionService;
        this.paymentService = paymentService;
        this.shipmentMapper = shipmentMapper;
    }

    /**
     * Pure side-effect-free quote endpoint: calculates OSRM/Haversine route, deterministic INR fare,
     * and rule-clamped Groq suggestion without writing to MySQL.
     */
    @Transactional(readOnly = true)
    public ShipmentQuoteResponse calculateQuote(ShipmentQuoteRequest request) {
        ComputedQuote computed = computeServerSideQuote(
                request.pickupLat(),
                request.pickupLng(),
                request.dropLat(),
                request.dropLng(),
                request.weightKg(),
                request.lengthCm(),
                request.widthCm(),
                request.heightCm(),
                request.packageCategory(),
                request.packageDescription(),
                request.fragile(),
                request.vehicleTypeCode(),
                request.gstApplicable()
        );

        List<VehicleOptionQuoteResponse> optionQuotes = pricingService.buildOptionQuotes(
                computed.eligibleVehicles(),
                computed.route().distanceKm(),
                request.gstApplicable(),
                computed.advice().aiSuggestedVehicleCode()
        );

        return new ShipmentQuoteResponse(
                computed.route().distanceKm(),
                computed.route().estimatedDurationMinutes(),
                computed.route().source(),
                computed.selectedVehicle().getCode(),
                computed.selectedVehicle().getName(),
                computed.fare().baseFare(),
                computed.fare().distanceFare(),
                computed.fare().subtotalFare(),
                computed.fare().gstApplicable(),
                computed.fare().gstRatePercent(),
                computed.fare().gstAmount(),
                computed.fare().totalFare(),
                CURRENCY_INR,
                computed.advice().ruleBasedMinimumVehicle().getCode(),
                computed.advice().aiSuggestedVehicleCode(),
                computed.advice().source(),
                computed.advice().note(),
                optionQuotes
        );
    }

    @Transactional
    public ShipmentDetailResponse createShipment(Long customerUserId, ShipmentCreateRequest request) {
        User customer = userRepository.findById(customerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer account not found"));

        if (customer.getRole() != Role.CUSTOMER) {
            throw new ForbiddenOperationException("Only customer accounts can book shipments");
        }

        // Recompute distance and INR fare strictly on the server so clients can never tamper with prices
        ComputedQuote computed = computeServerSideQuote(
                request.pickupLat(),
                request.pickupLng(),
                request.dropLat(),
                request.dropLng(),
                request.weightKg(),
                request.lengthCm(),
                request.widthCm(),
                request.heightCm(),
                request.packageCategory(),
                request.packageDescription(),
                request.fragile(),
                request.vehicleTypeCode(),
                request.gstApplicable()
        );

        String trackingCode = generateUniqueTrackingCode();

        Shipment shipment = Shipment.builder()
                .trackingCode(trackingCode)
                .customer(customer)
                .vehicleType(computed.selectedVehicle())
                .pickupAddress(request.pickupAddress().trim())
                .pickupCity(request.pickupCity().trim())
                .pickupLat(request.pickupLat())
                .pickupLng(request.pickupLng())
                .pickupContactName(request.pickupContactName().trim())
                .pickupContactPhone(request.pickupContactPhone().trim())
                .dropAddress(request.dropAddress().trim())
                .dropCity(request.dropCity().trim())
                .dropLat(request.dropLat())
                .dropLng(request.dropLng())
                .dropContactName(request.dropContactName().trim())
                .dropContactPhone(request.dropContactPhone().trim())
                .packageDescription(request.packageDescription().trim())
                .packageCategory(request.packageCategory())
                .weightKg(request.weightKg().setScale(2, RoundingMode.HALF_UP))
                .lengthCm(request.lengthCm())
                .widthCm(request.widthCm())
                .heightCm(request.heightCm())
                .fragile(request.fragile())
                .routeSource(computed.route().source())
                .distanceKm(computed.route().distanceKm())
                .estimatedDurationMinutes(computed.route().estimatedDurationMinutes())
                .baseFare(computed.fare().baseFare())
                .distanceFare(computed.fare().distanceFare())
                .subtotalFare(computed.fare().subtotalFare())
                .gstApplicable(computed.fare().gstApplicable())
                .gstRatePercent(computed.fare().gstRatePercent())
                .gstAmount(computed.fare().gstAmount())
                .totalFare(computed.fare().totalFare())
                .recommendedVehicleCode(computed.advice().aiSuggestedVehicleCode())
                .aiSuggestionNote(computed.advice().note())
                .status(ShipmentStatus.PENDING_PAYMENT)
                .build();

        Shipment saved = shipmentRepository.save(shipment);

        transitionService.recordInitialCreation(
                saved,
                customer,
                "Shipment booked; awaiting payment"
        );

        log.info("Shipment booked: id={}, trackingCode={}, customerId={}, vehicle={}, totalFare={}",
                saved.getId(), saved.getTrackingCode(), customer.getId(),
                computed.selectedVehicle().getCode(), saved.getTotalFare());

        return shipmentMapper.toDetail(saved);
    }

    @Transactional(readOnly = true)
    public PagedResponse<ShipmentSummaryResponse> listMyShipments(
            Long customerUserId,
            ShipmentStatus status,
            int page,
            int size
    ) {
        Pageable pageable = buildPageable(page, size);
        Page<Shipment> result = shipmentRepository.findCustomerShipments(customerUserId, status, pageable);
        List<ShipmentSummaryResponse> content = result.getContent().stream()
                .map(shipmentMapper::toSummary)
                .toList();

        return new PagedResponse<>(
                content,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isLast()
        );
    }

    @Transactional(readOnly = true)
    public ShipmentDetailResponse getShipmentDetail(Long shipmentId, UserPrincipal requester) {
        Shipment shipment = getShipmentWithDetailsOrThrow(shipmentId);
        assertCanViewShipment(shipment, requester);
        return shipmentMapper.toDetail(shipment);
    }

    @Transactional(readOnly = true)
    public List<ShipmentStatusHistoryResponse> getShipmentHistory(Long shipmentId, UserPrincipal requester) {
        Shipment shipment = getShipmentWithDetailsOrThrow(shipmentId);
        assertCanViewShipment(shipment, requester);
        return historyRepository.findByShipmentIdWithActor(shipment.getId()).stream()
                .map(shipmentMapper::toHistoryResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ShipmentTrackingResponse getTrackingSnapshot(Long shipmentId, UserPrincipal requester) {
        Shipment shipment = getShipmentWithDetailsOrThrow(shipmentId);
        assertCanViewShipment(shipment, requester);
        return shipmentMapper.toTrackingResponse(shipment);
    }

    @Transactional
    public ShipmentDetailResponse cancelShipment(
            Long shipmentId,
            UserPrincipal requester,
            ShipmentCancelRequest request
    ) {
        Shipment shipment = getShipmentWithDetailsOrThrow(shipmentId);
        User actor = userRepository.findById(requester.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));

        if (requester.getRole() == Role.CUSTOMER) {
            if (!shipment.getCustomer().getId().equals(requester.getId())) {
                throw new ForbiddenOperationException("You can only cancel your own shipments");
            }
            if (!stateMachine.canCustomerCancel(shipment.getStatus())) {
                throw new BadRequestException(
                        "Shipment cannot be cancelled in status " + shipment.getStatus()
                                + " (customer cancellation is allowed only before PICKED_UP)"
                );
            }
        } else if (requester.getRole() != Role.ADMIN) {
            throw new ForbiddenOperationException("Only the booking customer or an admin can cancel a shipment");
        }

        String trimmedReason = request.reason().trim();
        ShipmentActorRole actorRole = requester.getRole() == Role.ADMIN
                ? ShipmentActorRole.ADMIN
                : ShipmentActorRole.CUSTOMER;

        Instant now = Instant.now();

        // Cancel any active 60s job offers if shipment was in SEARCHING_DRIVER
        for (DriverJobOffer offer : offerRepository.findPendingOffersByShipmentId(shipment.getId())) {
            offer.setStatus(DriverJobOfferStatus.CANCELLED);
            offer.setRespondedAt(now);
            offerRepository.save(offer);
        }

        // Release assigned driver back to AVAILABLE if cancelled while ASSIGNED (before PICKED_UP)
        DriverProfile assignedDriver = shipment.getAssignedDriver();
        if (assignedDriver != null) {
            assignedDriver.setAvailable(true);
            driverProfileRepository.save(assignedDriver);
        }

        // Cancel open payment orders or issue a full Razorpay refund if already paid before pickup
        Optional<Payment> refundedPayment = paymentService.cancelOrRefundForShipment(shipment, trimmedReason);
        String historyNote = refundedPayment
                .map(p -> truncateNote(trimmedReason + " [Full refund initiated: " + p.getRazorpayRefundId() + "]"))
                .orElse(trimmedReason);

        transitionStatus(shipment, ShipmentStatus.CANCELLED, actor, actorRole, historyNote);
        shipment.setCancellationReason(trimmedReason);
        shipment.setCancelledAt(now);

        Shipment updated = shipmentRepository.save(shipment);
        log.info("Shipment cancelled: id={}, trackingCode={}, actorRole={}, refunded={}",
                updated.getId(), updated.getTrackingCode(), actorRole, refundedPayment.isPresent());
        return shipmentMapper.toDetail(updated);
    }

    /**
     * Centralized transition helper used across booking, payment (Phase 4), and delivery (Phase 5).
     */
    @Transactional
    public void transitionStatus(
            Shipment shipment,
            ShipmentStatus targetStatus,
            User actor,
            ShipmentActorRole actorRole,
            String note
    ) {
        transitionService.transition(shipment, targetStatus, actor, actorRole, note);
    }

    private Shipment getShipmentWithDetailsOrThrow(Long shipmentId) {
        return shipmentRepository.findByIdWithDetails(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found with id: " + shipmentId));
    }

    private void assertCanViewShipment(Shipment shipment, UserPrincipal requester) {
        if (requester.getRole() == Role.ADMIN) {
            return;
        }
        if (requester.getRole() == Role.CUSTOMER
                && shipment.getCustomer().getId().equals(requester.getId())) {
            return;
        }
        if (requester.getRole() == Role.DRIVER
                && shipment.getAssignedDriver() != null
                && shipment.getAssignedDriver().getUser().getId().equals(requester.getId())) {
            return;
        }
        throw new ForbiddenOperationException("You do not have permission to view this shipment");
    }

    private ComputedQuote computeServerSideQuote(
            double pickupLat,
            double pickupLng,
            double dropLat,
            double dropLng,
            BigDecimal weightKg,
            int lengthCm,
            int widthCm,
            int heightCm,
            PackageCategory category,
            String description,
            boolean fragile,
            String requestedVehicleCode,
            boolean gstApplicable
    ) {
        List<VehicleType> activeVehicles = vehicleTypeRepository.findByActiveTrueOrderByBaseFareAsc();
        if (activeVehicles.isEmpty()) {
            throw new BadRequestException("Vehicle catalog is currently unavailable");
        }

        List<VehicleType> eligibleVehicles = suggestionService.filterEligibleVehicles(
                activeVehicles, weightKg, lengthCm, widthCm, heightCm
        );

        VehicleSuggestionService.VehicleAdvice advice = suggestionService.suggestVehicle(
                eligibleVehicles, weightKg, lengthCm, widthCm, heightCm, category, description, fragile
        );

        VehicleType selectedVehicle = pricingService.selectAndValidateVehicle(
                requestedVehicleCode, activeVehicles, eligibleVehicles, advice.ruleBasedMinimumVehicle()
        );

        RoutingAndGeocodingService.RouteEstimate route = routingService.calculateRoute(
                pickupLat, pickupLng, dropLat, dropLng
        );

        PricingService.FareBreakdown fare = pricingService.calculateFare(
                selectedVehicle, route.distanceKm(), gstApplicable
        );

        return new ComputedQuote(eligibleVehicles, advice, selectedVehicle, route, fare);
    }

    private static String truncateNote(String note) {
        return note.length() <= 300 ? note : note.substring(0, 300);
    }

    private String generateUniqueTrackingCode() {
        String datePart = LocalDate.now(ZoneOffset.UTC).format(TRACKING_DATE_FORMAT);
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder suffix = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                suffix.append(TRACKING_ALPHABET.charAt(secureRandom.nextInt(TRACKING_ALPHABET.length())));
            }
            String candidate = "AS-" + datePart + "-" + suffix;
            if (!shipmentRepository.existsByTrackingCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate a unique shipment tracking code");
    }

    private static Pageable buildPageable(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private record ComputedQuote(
            List<VehicleType> eligibleVehicles,
            VehicleSuggestionService.VehicleAdvice advice,
            VehicleType selectedVehicle,
            RoutingAndGeocodingService.RouteEstimate route,
            PricingService.FareBreakdown fare
    ) {
    }
}
