package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.shipment.config.PricingProperties;
import com.sam.apnasamaan.shipment.dto.VehicleOptionQuoteResponse;
import com.sam.apnasamaan.shipment.entity.VehicleType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Deterministic server-side INR fare engine.
 * Formula:
 *   distanceFare = round(distanceKm * perKmRate, 2)
 *   subtotalFare = baseFare + distanceFare
 *   gstAmount    = gstApplicable ? round(subtotalFare * gstPercent / 100, 2) : 0.00
 *   totalFare    = subtotalFare + gstAmount
 */
@Service
public class PricingService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO_INR = new BigDecimal("0.00");

    private final PricingProperties pricingProperties;

    public PricingService(PricingProperties pricingProperties) {
        this.pricingProperties = pricingProperties;
    }

    public FareBreakdown calculateFare(VehicleType vehicleType, BigDecimal distanceKm, boolean gstApplicable) {
        BigDecimal normalizedDistance = distanceKm.setScale(2, RoundingMode.HALF_UP);
        BigDecimal baseFare = vehicleType.getBaseFare().setScale(2, RoundingMode.HALF_UP);
        BigDecimal distanceFare = normalizedDistance.multiply(vehicleType.getPerKmRate())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal subtotalFare = baseFare.add(distanceFare).setScale(2, RoundingMode.HALF_UP);

        BigDecimal gstRatePercent = gstApplicable
                ? pricingProperties.gstPercent().setScale(2, RoundingMode.HALF_UP)
                : ZERO_INR;

        BigDecimal gstAmount = gstApplicable
                ? subtotalFare.multiply(gstRatePercent).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP)
                : ZERO_INR;

        BigDecimal totalFare = subtotalFare.add(gstAmount).setScale(2, RoundingMode.HALF_UP);

        return new FareBreakdown(
                baseFare,
                distanceFare,
                subtotalFare,
                gstApplicable,
                gstRatePercent,
                gstAmount,
                totalFare
        );
    }

    public VehicleType selectAndValidateVehicle(
            String requestedVehicleCode,
            List<VehicleType> allActiveVehicles,
            List<VehicleType> eligibleVehicles,
            VehicleType recommendedMinimum
    ) {
        if (requestedVehicleCode == null || requestedVehicleCode.isBlank()) {
            return recommendedMinimum;
        }

        String code = requestedVehicleCode.trim();
        VehicleType chosen = allActiveVehicles.stream()
                .filter(vt -> vt.getCode().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "Invalid vehicle type '" + code + "'. Allowed codes: BIKE, CARGO_AUTO, MINI_TRUCK, TRUCK"
                ));

        boolean fitsChosen = eligibleVehicles.stream()
                .anyMatch(vt -> vt.getCode().equalsIgnoreCase(chosen.getCode()));
        if (!fitsChosen) {
            throw new BadRequestException(
                    "Selected vehicle '" + chosen.getCode() + "' cannot carry this package. "
                            + "Minimum required vehicle is '" + recommendedMinimum.getCode() + "'"
            );
        }

        return chosen;
    }

    public List<VehicleOptionQuoteResponse> buildOptionQuotes(
            List<VehicleType> eligibleVehicles,
            BigDecimal distanceKm,
            boolean gstApplicable,
            String recommendedCode
    ) {
        return eligibleVehicles.stream()
                .map(vt -> {
                    FareBreakdown fb = calculateFare(vt, distanceKm, gstApplicable);
                    return new VehicleOptionQuoteResponse(
                            vt.getCode(),
                            vt.getName(),
                            vt.getMaxWeightKg(),
                            fb.baseFare(),
                            vt.getPerKmRate(),
                            fb.subtotalFare(),
                            fb.gstAmount(),
                            fb.totalFare(),
                            vt.getCode().equalsIgnoreCase(recommendedCode)
                    );
                })
                .toList();
    }

    public record FareBreakdown(
            BigDecimal baseFare,
            BigDecimal distanceFare,
            BigDecimal subtotalFare,
            boolean gstApplicable,
            BigDecimal gstRatePercent,
            BigDecimal gstAmount,
            BigDecimal totalFare
    ) {
    }
}
