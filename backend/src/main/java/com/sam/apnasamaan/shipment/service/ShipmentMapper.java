package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.common.util.GeoDistanceUtil;
import com.sam.apnasamaan.driver.entity.DriverProfile;
import com.sam.apnasamaan.shipment.dto.ShipmentDetailResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentStatusHistoryResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentSummaryResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentTrackingResponse;
import com.sam.apnasamaan.shipment.entity.Shipment;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import com.sam.apnasamaan.shipment.entity.ShipmentStatusHistory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

@Component
public class ShipmentMapper {

    private static final String CURRENCY_INR = "INR";
    private static final Set<ShipmentStatus> LIVE_GPS_VISIBLE_STATUSES = EnumSet.of(
            ShipmentStatus.ASSIGNED,
            ShipmentStatus.PICKED_UP,
            ShipmentStatus.IN_TRANSIT
    );

    private final ShipmentStateMachine stateMachine;

    public ShipmentMapper(ShipmentStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    public ShipmentSummaryResponse toSummary(Shipment shipment) {
        DriverProfile driver = shipment.getAssignedDriver();
        String driverName = (driver != null && driver.getUser() != null) ? driver.getUser().getName() : null;

        return new ShipmentSummaryResponse(
                shipment.getId(),
                shipment.getTrackingCode(),
                shipment.getStatus(),
                shipment.getVehicleType().getCode(),
                shipment.getVehicleType().getName(),
                shipment.getPickupCity(),
                shipment.getPickupAddress(),
                shipment.getDropCity(),
                shipment.getDropAddress(),
                shipment.getDistanceKm(),
                shipment.getTotalFare(),
                shipment.isGstApplicable(),
                shipment.getCustomer().getName(),
                driverName,
                shipment.getCreatedAt()
        );
    }

    public ShipmentDetailResponse toDetail(Shipment shipment) {
        DriverProfile driver = shipment.getAssignedDriver();
        Long driverProfileId = driver != null ? driver.getId() : null;
        String driverName = (driver != null && driver.getUser() != null) ? driver.getUser().getName() : null;
        String driverVehicleNumber = driver != null ? driver.getVehicleNumber() : null;

        return new ShipmentDetailResponse(
                shipment.getId(),
                shipment.getTrackingCode(),
                shipment.getStatus(),
                stateMachine.canCustomerCancel(shipment.getStatus()),
                shipment.getCustomer().getId(),
                shipment.getCustomer().getName(),
                shipment.getCustomer().getEmail(),
                driverProfileId,
                driverName,
                driverVehicleNumber,
                shipment.getVehicleType().getCode(),
                shipment.getVehicleType().getName(),
                shipment.getRecommendedVehicleCode(),
                shipment.getAiSuggestionNote(),
                shipment.getPickupAddress(),
                shipment.getPickupCity(),
                shipment.getPickupLat(),
                shipment.getPickupLng(),
                shipment.getPickupContactName(),
                shipment.getPickupContactPhone(),
                shipment.getDropAddress(),
                shipment.getDropCity(),
                shipment.getDropLat(),
                shipment.getDropLng(),
                shipment.getDropContactName(),
                shipment.getDropContactPhone(),
                shipment.getPackageDescription(),
                shipment.getPackageCategory(),
                shipment.getWeightKg(),
                shipment.getLengthCm(),
                shipment.getWidthCm(),
                shipment.getHeightCm(),
                shipment.isFragile(),
                shipment.getRouteSource(),
                shipment.getDistanceKm(),
                shipment.getEstimatedDurationMinutes(),
                shipment.getBaseFare(),
                shipment.getDistanceFare(),
                shipment.getSubtotalFare(),
                shipment.isGstApplicable(),
                shipment.getGstRatePercent(),
                shipment.getGstAmount(),
                shipment.getTotalFare(),
                CURRENCY_INR,
                shipment.getCancellationReason(),
                shipment.getCancelledAt(),
                shipment.getPickedUpAt(),
                shipment.getDeliveredAt(),
                shipment.getCreatedAt(),
                shipment.getUpdatedAt()
        );
    }

    public ShipmentStatusHistoryResponse toHistoryResponse(ShipmentStatusHistory entry) {
        String actorName = entry.getChangedByUser() != null ? entry.getChangedByUser().getName() : "System";
        return new ShipmentStatusHistoryResponse(
                entry.getId(),
                entry.getFromStatus(),
                entry.getToStatus(),
                entry.getChangedByRole(),
                actorName,
                entry.getNote(),
                entry.getCreatedAt()
        );
    }

    public ShipmentTrackingResponse toTrackingResponse(Shipment shipment) {
        DriverProfile driver = shipment.getAssignedDriver();
        Long driverProfileId = driver != null ? driver.getId() : null;
        String driverName = (driver != null && driver.getUser() != null) ? driver.getUser().getName() : null;
        String driverPhone = (driver != null && driver.getUser() != null) ? driver.getUser().getPhone() : null;
        String driverVehicleNumber = driver != null ? driver.getVehicleNumber() : null;

        Double driverLat = null;
        Double driverLng = null;
        Instant driverLocTime = null;
        BigDecimal distanceToTargetKm = null;

        // Share live driver GPS only while delivery is active (ASSIGNED / PICKED_UP / IN_TRANSIT)
        if (driver != null && LIVE_GPS_VISIBLE_STATUSES.contains(shipment.getStatus())) {
            driverLat = driver.getCurrentLat();
            driverLng = driver.getCurrentLng();
            driverLocTime = driver.getLocationUpdatedAt();

            if (driverLat != null && driverLng != null) {
                double targetLat = shipment.getStatus() == ShipmentStatus.ASSIGNED
                        ? shipment.getPickupLat()
                        : shipment.getDropLat();
                double targetLng = shipment.getStatus() == ShipmentStatus.ASSIGNED
                        ? shipment.getPickupLng()
                        : shipment.getDropLng();
                double km = GeoDistanceUtil.haversineKm(driverLat, driverLng, targetLat, targetLng);
                distanceToTargetKm = BigDecimal.valueOf(km).setScale(2, RoundingMode.HALF_UP);
            }
        }

        return new ShipmentTrackingResponse(
                shipment.getId(),
                shipment.getTrackingCode(),
                shipment.getStatus(),
                shipment.getPickupAddress(),
                shipment.getPickupCity(),
                shipment.getPickupLat(),
                shipment.getPickupLng(),
                shipment.getDropAddress(),
                shipment.getDropCity(),
                shipment.getDropLat(),
                shipment.getDropLng(),
                driverProfileId,
                driverName,
                driverPhone,
                driverVehicleNumber,
                shipment.getVehicleType().getCode(),
                shipment.getVehicleType().getName(),
                driverLat,
                driverLng,
                driverLocTime,
                distanceToTargetKm,
                shipment.getPickedUpAt(),
                shipment.getDeliveredAt(),
                shipment.getUpdatedAt()
        );
    }
}
