package com.sam.apnasamaan.shipment.dto;

import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Live tracking snapshot polled every ~5s by customer, driver, or admin")
public record ShipmentTrackingResponse(
        Long shipmentId,
        String trackingCode,
        ShipmentStatus status,

        String pickupAddress,
        String pickupCity,
        double pickupLat,
        double pickupLng,

        String dropAddress,
        String dropCity,
        double dropLat,
        double dropLng,

        Long assignedDriverProfileId,
        String assignedDriverName,
        String assignedDriverPhone,
        String assignedDriverVehicleNumber,
        String vehicleTypeCode,
        String vehicleTypeName,

        Double driverLat,
        Double driverLng,
        Instant driverLocationUpdatedAt,
        BigDecimal distanceToTargetKm,

        Instant pickedUpAt,
        Instant deliveredAt,
        Instant updatedAt
) {
}
