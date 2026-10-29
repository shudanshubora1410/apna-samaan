package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.auth.security.UserPrincipal;
import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.common.exception.ConflictException;
import com.sam.apnasamaan.common.exception.ForbiddenOperationException;
import com.sam.apnasamaan.common.exception.ResourceNotFoundException;
import com.sam.apnasamaan.common.mail.EmailService;
import com.sam.apnasamaan.common.mail.ShipmentAssignedMailData;
import com.sam.apnasamaan.common.util.GeoDistanceUtil;
import com.sam.apnasamaan.driver.entity.DriverProfile;
import com.sam.apnasamaan.driver.event.DriverOnlineEvent;
import com.sam.apnasamaan.driver.repository.DriverProfileRepository;
import com.sam.apnasamaan.payment.entity.Payment;
import com.sam.apnasamaan.payment.service.PaymentService;
import com.sam.apnasamaan.shipment.config.MatchingProperties;
import com.sam.apnasamaan.shipment.dto.DriverJobOfferResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentDetailResponse;
import com.sam.apnasamaan.shipment.entity.DriverJobOffer;
import com.sam.apnasamaan.shipment.entity.DriverJobOfferStatus;
import com.sam.apnasamaan.shipment.entity.Shipment;
import com.sam.apnasamaan.shipment.entity.ShipmentActorRole;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import com.sam.apnasamaan.shipment.event.ShipmentReadyForMatchingEvent;
import com.sam.apnasamaan.shipment.repository.DriverJobOfferRepository;
import com.sam.apnasamaan.shipment.repository.ShipmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class DriverMatchingService {

    private final ShipmentRepository shipmentRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final DriverJobOfferRepository offerRepository;
    private final ShipmentTransitionService transitionService;
    private final PaymentService paymentService;
    private final EmailService emailService;
    private final ShipmentMapper shipmentMapper;
    private final MatchingProperties matchingProperties;

    public DriverMatchingService(
            ShipmentRepository shipmentRepository,
            DriverProfileRepository driverProfileRepository,
            DriverJobOfferRepository offerRepository,
            ShipmentTransitionService transitionService,
            PaymentService paymentService,
            EmailService emailService,
            ShipmentMapper shipmentMapper,
            MatchingProperties matchingProperties
    ) {
        this.shipmentRepository = shipmentRepository;
        this.driverProfileRepository = driverProfileRepository;
        this.offerRepository = offerRepository;
        this.transitionService = transitionService;
        this.paymentService = paymentService;
        this.emailService = emailService;
        this.shipmentMapper = shipmentMapper;
        this.matchingProperties = matchingProperties;
    }

    @EventListener
    @Transactional
    public void onShipmentReadyForMatching(ShipmentReadyForMatchingEvent event) {
        dispatchNextOfferIfPossible(event.shipmentId());
    }

    @EventListener
    @Transactional
    public void onDriverOnline(DriverOnlineEvent event) {
        List<Shipment> searching = shipmentRepository.findShipmentsSearchingForDriver();
        for (Shipment shipment : searching) {
            if (shipment.getPickupCity().equalsIgnoreCase(event.city())) {
                dispatchNextOfferIfPossible(shipment.getId());
            }
        }
    }

    /**
     * Ranks APPROVED + ONLINE + AVAILABLE same-city matching-vehicle drivers by Haversine distance
     * and dispatches a single sequential 60-second offer to the nearest unoffered driver.
     */
    @Transactional
    public Optional<DriverJobOffer> dispatchNextOfferIfPossible(Long shipmentId) {
        Shipment shipment = shipmentRepository.findByIdWithDetails(shipmentId).orElse(null);
        if (shipment == null || shipment.getStatus() != ShipmentStatus.SEARCHING_DRIVER) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        if (shipment.getSearchStartedAt() == null) {
            shipment.setSearchStartedAt(now);
            shipmentRepository.save(shipment);
        }

        // Sequential rule: wait while an active unexpired 60s offer is already pending
        if (offerRepository.existsByShipmentIdAndStatusAndExpiresAtAfter(
                shipmentId, DriverJobOfferStatus.PENDING, now)) {
            return Optional.empty();
        }

        List<DriverProfile> candidates = driverProfileRepository.findEligibleDriversForShipment(
                shipment.getPickupCity(),
                shipment.getVehicleType().getId(),
                shipmentId,
                now
        );

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        RankedDriver nearest = candidates.stream()
                .map(dp -> rankCandidate(dp, shipment.getPickupLat(), shipment.getPickupLng()))
                .min(Comparator
                        .comparing((RankedDriver r) -> r.distanceKm() == null)
                        .thenComparing(r -> r.distanceKm() != null ? r.distanceKm() : BigDecimal.ZERO)
                        .thenComparing(r -> r.driver().getId()))
                .orElseThrow();

        int nextSequence = (int) offerRepository.countByShipmentId(shipmentId) + 1;
        Instant expiresAt = now.plusSeconds(matchingProperties.offerTimeoutSeconds());

        DriverJobOffer offer = DriverJobOffer.builder()
                .shipment(shipment)
                .driverProfile(nearest.driver())
                .sequenceNumber(nextSequence)
                .distanceToPickupKm(nearest.distanceKm())
                .status(DriverJobOfferStatus.PENDING)
                .offeredAt(now)
                .expiresAt(expiresAt)
                .build();

        DriverJobOffer saved = offerRepository.save(offer);
        log.info("Dispatched job offer: offerId={}, shipmentId={}, driverProfileId={}, seq={}, distanceKm={}, expiresAt={}",
                saved.getId(), shipmentId, nearest.driver().getId(), nextSequence, nearest.distanceKm(), expiresAt);
        return Optional.of(saved);
    }

    @Transactional(readOnly = true)
    public List<DriverJobOfferResponse> listPendingOffersForDriver(UserPrincipal driverPrincipal) {
        DriverProfile profile = driverProfileRepository.findByUserIdWithDetails(driverPrincipal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found"));

        Instant now = Instant.now();
        return offerRepository.findActivePendingOffersForDriver(profile.getId(), now).stream()
                .map(offer -> toOfferResponse(offer, now))
                .toList();
    }

    @Transactional
    public ShipmentDetailResponse acceptOffer(Long offerId, UserPrincipal driverPrincipal) {
        DriverJobOffer offer = offerRepository.findByIdWithDetails(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Job offer not found with id: " + offerId));

        DriverProfile driverProfile = offer.getDriverProfile();
        if (!driverProfile.getUser().getId().equals(driverPrincipal.getId())) {
            throw new ForbiddenOperationException("You can only accept job offers sent to your driver account");
        }

        Instant now = Instant.now();
        if (offer.getStatus() != DriverJobOfferStatus.PENDING) {
            throw new ConflictException("Job offer is no longer pending (current status: " + offer.getStatus() + ")");
        }
        if (!now.isBefore(offer.getExpiresAt())) {
            offer.setStatus(DriverJobOfferStatus.EXPIRED);
            offer.setRespondedAt(now);
            offerRepository.save(offer);
            dispatchNextOfferIfPossible(offer.getShipment().getId());
            throw new BadRequestException("This 60-second job offer has already expired");
        }

        Shipment shipment = offer.getShipment();
        if (shipment.getStatus() != ShipmentStatus.SEARCHING_DRIVER || shipment.getAssignedDriver() != null) {
            throw new ConflictException("Shipment is no longer available for assignment");
        }

        // Layer 1 concurrency guard: atomic conditional SQL UPDATE ensures driver is still ONLINE + AVAILABLE
        int claimed = driverProfileRepository.claimDriverIfAvailable(driverProfile.getId());
        if (claimed == 0) {
            throw new ConflictException("Driver must be APPROVED, ONLINE, and AVAILABLE to accept a job");
        }
        driverProfile.setAvailable(false);

        // Layer 2 concurrency guard: @Version optimistic locking on both DriverJobOffer and Shipment
        offer.setStatus(DriverJobOfferStatus.ACCEPTED);
        offer.setRespondedAt(now);
        offerRepository.save(offer);

        shipment.setAssignedDriver(driverProfile);
        String historyNote = "Assigned to driver " + driverProfile.getUser().getName()
                + " (" + driverProfile.getVehicleNumber() + ")";
        transitionService.transition(
                shipment,
                ShipmentStatus.ASSIGNED,
                driverProfile.getUser(),
                ShipmentActorRole.DRIVER,
                historyNote
        );
        Shipment savedShipment = shipmentRepository.save(shipment);

        log.info("Driver accepted job offer: offerId={}, shipmentId={}, driverProfileId={}",
                offerId, savedShipment.getId(), driverProfile.getId());

        emailService.sendShipmentAssignedEmail(new ShipmentAssignedMailData(
                savedShipment.getCustomer().getEmail(),
                savedShipment.getCustomer().getName(),
                savedShipment.getTrackingCode(),
                driverProfile.getUser().getName(),
                driverProfile.getUser().getPhone(),
                savedShipment.getVehicleType().getName(),
                driverProfile.getVehicleNumber(),
                savedShipment.getPickupAddress(),
                savedShipment.getPickupCity(),
                savedShipment.getDropAddress(),
                savedShipment.getDropCity()
        ));

        return shipmentMapper.toDetail(savedShipment);
    }

    @Transactional
    public void declineOffer(Long offerId, UserPrincipal driverPrincipal) {
        DriverJobOffer offer = offerRepository.findByIdWithDetails(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Job offer not found with id: " + offerId));

        if (!offer.getDriverProfile().getUser().getId().equals(driverPrincipal.getId())) {
            throw new ForbiddenOperationException("You can only decline job offers sent to your driver account");
        }
        if (offer.getStatus() != DriverJobOfferStatus.PENDING) {
            throw new BadRequestException("Job offer is no longer pending (current status: " + offer.getStatus() + ")");
        }

        offer.setStatus(DriverJobOfferStatus.DECLINED);
        offer.setRespondedAt(Instant.now());
        offerRepository.save(offer);

        log.info("Driver declined job offer: offerId={}, shipmentId={}, driverProfileId={}",
                offerId, offer.getShipment().getId(), offer.getDriverProfile().getId());

        // Immediately advance to the next Haversine-nearest eligible driver
        dispatchNextOfferIfPossible(offer.getShipment().getId());
    }

    /**
     * Periodic sweep: expires timed-out 60s offers, advances sequential offers to the next driver,
     * and transitions timed-out SEARCHING_DRIVER shipments to FAILED with automatic Razorpay refund.
     */
    @Transactional
    public void processTimeoutsAndPendingSearches() {
        Instant now = Instant.now();

        List<DriverJobOffer> expiredOffers = offerRepository.findExpiredPendingOffers(now);
        Set<Long> affectedShipmentIds = new LinkedHashSet<>();
        for (DriverJobOffer offer : expiredOffers) {
            offer.setStatus(DriverJobOfferStatus.EXPIRED);
            offer.setRespondedAt(now);
            offerRepository.save(offer);
            affectedShipmentIds.add(offer.getShipment().getId());
            log.info("Expired timed-out driver offer: offerId={}, shipmentId={}",
                    offer.getId(), offer.getShipment().getId());
        }

        for (Long shipmentId : affectedShipmentIds) {
            dispatchNextOfferIfPossible(shipmentId);
        }

        List<Shipment> searchingShipments = shipmentRepository.findShipmentsSearchingForDriver();
        for (Shipment shipment : searchingShipments) {
            if (shipment.getSearchStartedAt() == null) {
                shipment.setSearchStartedAt(now);
                shipmentRepository.save(shipment);
            }

            if (offerRepository.existsByShipmentIdAndStatusAndExpiresAtAfter(
                    shipment.getId(), DriverJobOfferStatus.PENDING, now)) {
                continue;
            }

            Optional<DriverJobOffer> newlyDispatched = dispatchNextOfferIfPossible(shipment.getId());
            if (newlyDispatched.isPresent()) {
                continue;
            }

            Instant searchDeadline = shipment.getSearchStartedAt()
                    .plusSeconds(matchingProperties.searchTimeoutSeconds());
            if (!now.isBefore(searchDeadline)) {
                failTimedOutShipmentAndRefund(shipment, now);
            }
        }
    }

    private void failTimedOutShipmentAndRefund(Shipment shipment, Instant now) {
        List<DriverJobOffer> pending = offerRepository.findPendingOffersByShipmentId(shipment.getId());
        for (DriverJobOffer p : pending) {
            p.setStatus(DriverJobOfferStatus.EXPIRED);
            p.setRespondedAt(now);
            offerRepository.save(p);
        }

        String baseReason = "No available driver matched within timeout";
        Optional<Payment> refunded = paymentService.cancelOrRefundForShipment(shipment, baseReason);
        String historyNote = refunded
                .map(p -> baseReason + " [Full refund initiated: " + p.getRazorpayRefundId() + "]")
                .orElse(baseReason);

        transitionService.transition(
                shipment,
                ShipmentStatus.FAILED,
                null,
                ShipmentActorRole.SYSTEM,
                historyNote
        );
        shipmentRepository.save(shipment);

        log.info("Shipment search timed out -> FAILED: shipmentId={}, trackingCode={}, refunded={}",
                shipment.getId(), shipment.getTrackingCode(), refunded.isPresent());
    }

    private static RankedDriver rankCandidate(DriverProfile driver, double pickupLat, double pickupLng) {
        if (driver.getCurrentLat() == null || driver.getCurrentLng() == null) {
            return new RankedDriver(driver, null);
        }
        double km = GeoDistanceUtil.haversineKm(
                driver.getCurrentLat(), driver.getCurrentLng(), pickupLat, pickupLng
        );
        BigDecimal roundedKm = BigDecimal.valueOf(km).setScale(2, RoundingMode.HALF_UP);
        return new RankedDriver(driver, roundedKm);
    }

    private static DriverJobOfferResponse toOfferResponse(DriverJobOffer offer, Instant now) {
        Shipment s = offer.getShipment();
        long remaining = Math.max(0L, Duration.between(now, offer.getExpiresAt()).getSeconds());

        return new DriverJobOfferResponse(
                offer.getId(),
                s.getId(),
                s.getTrackingCode(),
                offer.getSequenceNumber(),
                offer.getStatus(),
                offer.getDistanceToPickupKm(),
                s.getPickupAddress(),
                s.getPickupCity(),
                s.getPickupLat(),
                s.getPickupLng(),
                s.getDropAddress(),
                s.getDropCity(),
                s.getDropLat(),
                s.getDropLng(),
                s.getDistanceKm(),
                s.getEstimatedDurationMinutes(),
                s.getPackageDescription(),
                s.getPackageCategory(),
                s.getWeightKg(),
                s.isFragile(),
                s.getVehicleType().getCode(),
                s.getVehicleType().getName(),
                s.getTotalFare(),
                offer.getOfferedAt(),
                offer.getExpiresAt(),
                remaining
        );
    }

    private record RankedDriver(DriverProfile driver, BigDecimal distanceKm) {
    }
}
