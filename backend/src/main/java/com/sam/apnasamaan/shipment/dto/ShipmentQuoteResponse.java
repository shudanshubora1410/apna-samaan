package com.sam.apnasamaan.shipment.dto;

import com.sam.apnasamaan.shipment.entity.RouteSource;
import com.sam.apnasamaan.shipment.entity.SuggestionSource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Deterministic INR fare quote, road route estimate, and AI/rule-based vehicle suggestion")
public record ShipmentQuoteResponse(
        BigDecimal distanceKm,
        int estimatedDurationMinutes,
        RouteSource routeSource,
        String selectedVehicleCode,
        String selectedVehicleName,
        BigDecimal baseFare,
        BigDecimal distanceFare,
        BigDecimal subtotalFare,
        boolean gstApplicable,
        BigDecimal gstRatePercent,
        BigDecimal gstAmount,
        BigDecimal totalFare,
        String currency,
        String recommendedVehicleCode,
        String aiSuggestedVehicleCode,
        SuggestionSource suggestionSource,
        String suggestionNote,
        List<VehicleOptionQuoteResponse> eligibleVehicles
) {
}
