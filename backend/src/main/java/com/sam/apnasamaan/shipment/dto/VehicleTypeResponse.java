package com.sam.apnasamaan.shipment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Public vehicle tier details including capacity limits and INR rates")
public record VehicleTypeResponse(
        @Schema(example = "2") Long id,
        @Schema(example = "CARGO_AUTO") String code,
        @Schema(example = "Cargo Auto") String name,
        @Schema(example = "Three-wheeler cargo loader for cartons, appliances, and shop stock") String description,
        @Schema(example = "500.00") BigDecimal maxWeightKg,
        @Schema(example = "170") int maxLengthCm,
        @Schema(example = "120") int maxWidthCm,
        @Schema(example = "120") int maxHeightCm,
        @Schema(example = "180.00") BigDecimal baseFare,
        @Schema(example = "18.00") BigDecimal perKmRate
) {
}
