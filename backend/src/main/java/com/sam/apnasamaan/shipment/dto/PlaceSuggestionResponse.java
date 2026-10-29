package com.sam.apnasamaan.shipment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Geocoded place suggestion in India returned by the Nominatim wrapper")
public record PlaceSuggestionResponse(
        @Schema(example = "Connaught Place, New Delhi, Delhi, 110001, India")
        String displayName,
        @Schema(example = "New Delhi")
        String city,
        @Schema(example = "Delhi")
        String state,
        @Schema(example = "28.6315")
        double lat,
        @Schema(example = "77.2167")
        double lng
) {
}
