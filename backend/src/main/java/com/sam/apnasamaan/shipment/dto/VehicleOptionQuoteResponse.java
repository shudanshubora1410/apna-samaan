package com.sam.apnasamaan.shipment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Fare preview for a single eligible vehicle tier")
public record VehicleOptionQuoteResponse(
        String vehicleTypeCode,
        String vehicleTypeName,
        BigDecimal maxWeightKg,
        BigDecimal baseFare,
        BigDecimal perKmRate,
        BigDecimal subtotalFare,
        BigDecimal gstAmount,
        BigDecimal totalFare,
        boolean recommended
) {
}
