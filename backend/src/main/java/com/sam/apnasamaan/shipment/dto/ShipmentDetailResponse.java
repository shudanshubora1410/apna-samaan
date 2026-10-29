package com.sam.apnasamaan.shipment.dto;

import com.sam.apnasamaan.shipment.entity.PackageCategory;
import com.sam.apnasamaan.shipment.entity.RouteSource;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Full shipment booking detail with route, INR fare breakdown, and lifecycle timestamps")
public record ShipmentDetailResponse(
        Long id,
        String trackingCode,
        ShipmentStatus status,
        boolean cancellableByCustomer,

        Long customerId,
        String customerName,
        String customerEmail,

        Long assignedDriverProfileId,
        String assignedDriverName,
        String assignedDriverVehicleNumber,

        String vehicleTypeCode,
        String vehicleTypeName,
        String recommendedVehicleCode,
        String aiSuggestionNote,

        String pickupAddress,
        String pickupCity,
        double pickupLat,
        double pickupLng,
        String pickupContactName,
        String pickupContactPhone,

        String dropAddress,
        String dropCity,
        double dropLat,
        double dropLng,
        String dropContactName,
        String dropContactPhone,

        String packageDescription,
        PackageCategory packageCategory,
        BigDecimal weightKg,
        int lengthCm,
        int widthCm,
        int heightCm,
        boolean fragile,

        RouteSource routeSource,
        BigDecimal distanceKm,
        int estimatedDurationMinutes,

        BigDecimal baseFare,
        BigDecimal distanceFare,
        BigDecimal subtotalFare,
        boolean gstApplicable,
        BigDecimal gstRatePercent,
        BigDecimal gstAmount,
        BigDecimal totalFare,
        String currency,

        String cancellationReason,
        Instant cancelledAt,
        Instant pickedUpAt,
        Instant deliveredAt,
        Instant createdAt,
        Instant updatedAt
) {
}
