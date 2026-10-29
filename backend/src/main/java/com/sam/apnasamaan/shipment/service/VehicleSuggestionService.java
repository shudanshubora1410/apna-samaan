package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.shipment.entity.PackageCategory;
import com.sam.apnasamaan.shipment.entity.SuggestionSource;
import com.sam.apnasamaan.shipment.entity.VehicleType;

import java.math.BigDecimal;
import java.util.List;

/**
 * Advisory vehicle tier confirmation with strict rule-based floor.
 * Never sets or modifies shipment fares.
 */
public interface VehicleSuggestionService {

    List<VehicleType> filterEligibleVehicles(
            List<VehicleType> activeVehiclesOrderedByFare,
            BigDecimal weightKg,
            int lengthCm,
            int widthCm,
            int heightCm
    );

    VehicleAdvice suggestVehicle(
            List<VehicleType> eligibleVehicles,
            BigDecimal weightKg,
            int lengthCm,
            int widthCm,
            int heightCm,
            PackageCategory category,
            String description,
            boolean fragile
    );

    record VehicleAdvice(
            VehicleType ruleBasedMinimumVehicle,
            String aiSuggestedVehicleCode,
            SuggestionSource source,
            String note
    ) {
    }
}
