package com.sam.apnasamaan.shipment.dto;

import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Compact shipment summary for customer and admin paginated lists")
public record ShipmentSummaryResponse(
        Long id,
        String trackingCode,
        ShipmentStatus status,
        String vehicleTypeCode,
        String vehicleTypeName,
        String pickupCity,
        String pickupAddress,
        String dropCity,
        String dropAddress,
        BigDecimal distanceKm,
        BigDecimal totalFare,
        boolean gstApplicable,
        String customerName,
        String assignedDriverName,
        Instant createdAt
) {
}
