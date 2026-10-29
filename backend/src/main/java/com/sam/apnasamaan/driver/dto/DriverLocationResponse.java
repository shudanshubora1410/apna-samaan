package com.sam.apnasamaan.driver.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Acknowledges updated driver GPS coordinates")
public record DriverLocationResponse(
        Long driverProfileId,
        double lat,
        double lng,
        Instant locationUpdatedAt
) {
}
