package com.sam.apnasamaan.shipment.dto;

import com.sam.apnasamaan.shipment.entity.DriverJobOfferStatus;
import com.sam.apnasamaan.shipment.entity.PackageCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Sequential 60-second job offer presented to an online eligible driver")
public record DriverJobOfferResponse(
        Long offerId,
        Long shipmentId,
        String trackingCode,
        int sequenceNumber,
        DriverJobOfferStatus status,
        BigDecimal distanceToPickupKm,
        String pickupAddress,
        String pickupCity,
        double pickupLat,
        double pickupLng,
        String dropAddress,
        String dropCity,
        double dropLat,
        double dropLng,
        BigDecimal routeDistanceKm,
        int estimatedDurationMinutes,
        String packageDescription,
        PackageCategory packageCategory,
        BigDecimal weightKg,
        boolean fragile,
        String vehicleTypeCode,
        String vehicleTypeName,
        BigDecimal totalFare,
        Instant offeredAt,
        Instant expiresAt,
        long remainingSeconds
) {
}
