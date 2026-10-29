package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.shipment.dto.PlaceSuggestionResponse;
import com.sam.apnasamaan.shipment.entity.RouteSource;

import java.math.BigDecimal;
import java.util.List;

/**
 * Single backend abstraction wrapping OpenStreetMap routing (OSRM) and geocoding (Nominatim).
 */
public interface RoutingAndGeocodingService {

    RouteEstimate calculateRoute(double pickupLat, double pickupLng, double dropLat, double dropLng);

    List<PlaceSuggestionResponse> searchPlacesInIndia(String query, int limit);

    record RouteEstimate(
            BigDecimal distanceKm,
            int estimatedDurationMinutes,
            RouteSource source
    ) {
    }
}
