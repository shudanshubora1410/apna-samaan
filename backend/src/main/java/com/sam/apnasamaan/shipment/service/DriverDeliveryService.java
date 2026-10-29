package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.auth.security.UserPrincipal;
import com.sam.apnasamaan.common.dto.PagedResponse;
import com.sam.apnasamaan.common.exception.ForbiddenOperationException;
import com.sam.apnasamaan.common.exception.ResourceNotFoundException;
import com.sam.apnasamaan.common.mail.EmailService;
import com.sam.apnasamaan.common.mail.ShipmentDeliveredMailData;
import com.sam.apnasamaan.driver.entity.DriverProfile;
import com.sam.apnasamaan.driver.repository.DriverProfileRepository;
import com.sam.apnasamaan.shipment.dto.DeliveryNoteRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentDetailResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentSummaryResponse;
import com.sam.apnasamaan.shipment.entity.Shipment;
import com.sam.apnasamaan.shipment.entity.ShipmentActorRole;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import com.sam.apnasamaan.shipment.repository.ShipmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class DriverDeliveryService {

    private static final Set<ShipmentStatus> ACTIVE_DRIVER_STATUSES = EnumSet.of(
            ShipmentStatus.ASSIGNED,
            ShipmentStatus.PICKED_UP,
            ShipmentStatus.IN_TRANSIT
    );
    private static final int MAX_PAGE_SIZE = 50;

    private final ShipmentRepository shipmentRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final ShipmentTransitionService transitionService;
    private final EmailService emailService;
    private final ShipmentMapper shipmentMapper;

    public DriverDeliveryService(
            ShipmentRepository shipmentRepository,
            DriverProfileRepository driverProfileRepository,
            ShipmentTransitionService transitionService,
            EmailService emailService,
            ShipmentMapper shipmentMapper
    ) {
        this.shipmentRepository = shipmentRepository;
        this.driverProfileRepository = driverProfileRepository;
        this.transitionService = transitionService;
        this.emailService = emailService;
        this.shipmentMapper = shipmentMapper;
    }

    @Transactional(readOnly = true)
    public List<ShipmentDetailResponse> getActiveDeliveries(UserPrincipal driverPrincipal) {
        DriverProfile profile = loadDriverProfile(driverPrincipal.getId());
        return shipmentRepository.findActiveShipmentsForDriver(profile.getId(), ACTIVE_DRIVER_STATUSES).stream()
                .map(shipmentMapper::toDetail)
                .toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<ShipmentSummaryResponse> listDriverShipments(
            UserPrincipal driverPrincipal,
            ShipmentStatus status,
            int page,
            int size
    ) {
        DriverProfile profile = loadDriverProfile(driverPrincipal.getId());
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<ShipmentSummaryResponse> result = shipmentRepository
                .findDriverShipments(profile.getId(), status, pageable)
                .map(shipmentMapper::toSummary);
        return PagedResponse.from(result);
    }

    @Transactional
    public ShipmentDetailResponse markPickedUp(
            Long shipmentId,
            UserPrincipal driverPrincipal,
            DeliveryNoteRequest request
    ) {
        Shipment shipment = loadAssignedShipmentForDriver(shipmentId, driverPrincipal.getId());
        String note = resolveNote(request, "Package picked up from sender by driver");

        transitionService.transition(
                shipment,
                ShipmentStatus.PICKED_UP,
                shipment.getAssignedDriver().getUser(),
                ShipmentActorRole.DRIVER,
                note
        );
        shipment.setPickedUpAt(Instant.now());
        Shipment saved = shipmentRepository.save(shipment);

        log.info("Shipment marked PICKED_UP: shipmentId={}, trackingCode={}, driverProfileId={}",
                saved.getId(), saved.getTrackingCode(), saved.getAssignedDriver().getId());
        return shipmentMapper.toDetail(saved);
    }

    @Transactional
    public ShipmentDetailResponse markInTransit(
            Long shipmentId,
            UserPrincipal driverPrincipal,
            DeliveryNoteRequest request
    ) {
        Shipment shipment = loadAssignedShipmentForDriver(shipmentId, driverPrincipal.getId());
        String note = resolveNote(request, "Shipment is in transit to dropoff destination");

        transitionService.transition(
                shipment,
                ShipmentStatus.IN_TRANSIT,
                shipment.getAssignedDriver().getUser(),
                ShipmentActorRole.DRIVER,
                note
        );
        Shipment saved = shipmentRepository.save(shipment);

        log.info("Shipment marked IN_TRANSIT: shipmentId={}, trackingCode={}", saved.getId(), saved.getTrackingCode());
        return shipmentMapper.toDetail(saved);
    }

    @Transactional
    public ShipmentDetailResponse markDelivered(
            Long shipmentId,
            UserPrincipal driverPrincipal,
            DeliveryNoteRequest request
    ) {
        Shipment shipment = loadAssignedShipmentForDriver(shipmentId, driverPrincipal.getId());
        DriverProfile driver = shipment.getAssignedDriver();
        String note = resolveNote(request, "Shipment delivered to recipient");

        transitionService.transition(
                shipment,
                ShipmentStatus.DELIVERED,
                driver.getUser(),
                ShipmentActorRole.DRIVER,
                note
        );
        shipment.setDeliveredAt(Instant.now());
        Shipment saved = shipmentRepository.save(shipment);

        // Release driver back into the AVAILABLE pool for future dispatches
        driver.setAvailable(true);
        driverProfileRepository.save(driver);

        log.info("Shipment marked DELIVERED: shipmentId={}, trackingCode={}, driverProfileId={}",
                saved.getId(), saved.getTrackingCode(), driver.getId());

        emailService.sendShipmentDeliveredEmail(new ShipmentDeliveredMailData(
                saved.getCustomer().getEmail(),
                saved.getCustomer().getName(),
                saved.getTrackingCode(),
                driver.getUser().getName(),
                saved.getVehicleType().getName(),
                driver.getVehicleNumber(),
                saved.getPickupAddress(),
                saved.getPickupCity(),
                saved.getDropAddress(),
                saved.getDropCity(),
                saved.getTotalFare().toPlainString(),
                saved.getDeliveredAt()
        ));

        return shipmentMapper.toDetail(saved);
    }

    private DriverProfile loadDriverProfile(Long userId) {
        return driverProfileRepository.findByUserIdWithDetails(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found"));
    }

    private Shipment loadAssignedShipmentForDriver(Long shipmentId, Long driverUserId) {
        Shipment shipment = shipmentRepository.findByIdWithDetails(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found with id: " + shipmentId));

        if (shipment.getAssignedDriver() == null
                || !shipment.getAssignedDriver().getUser().getId().equals(driverUserId)) {
            throw new ForbiddenOperationException("Only the assigned driver can update this shipment's delivery status");
        }
        return shipment;
    }

    private static String resolveNote(DeliveryNoteRequest request, String defaultNote) {
        if (request == null || request.note() == null || request.note().isBlank()) {
            return defaultNote;
        }
        return request.note().trim();
    }
}
