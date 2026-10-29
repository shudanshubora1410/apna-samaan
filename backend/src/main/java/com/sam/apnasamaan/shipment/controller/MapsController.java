package com.sam.apnasamaan.shipment.controller;

import com.sam.apnasamaan.shipment.dto.PlaceSuggestionResponse;
import com.sam.apnasamaan.shipment.service.RoutingAndGeocodingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/maps")
@Tag(name = "Maps & Geocoding", description = "India address search backed by OpenStreetMap Nominatim")
public class MapsController {

    private final RoutingAndGeocodingService routingAndGeocodingService;

    public MapsController(RoutingAndGeocodingService routingAndGeocodingService) {
        this.routingAndGeocodingService = routingAndGeocodingService;
    }

    @GetMapping("/geocode")
    @Operation(summary = "Search addresses/places in India for pickup and dropoff autocomplete")
    public ResponseEntity<List<PlaceSuggestionResponse>> geocode(
            @Parameter(description = "Place or street query in India (min 2 chars)", example = "Connaught Place Delhi")
            @RequestParam("q") String query,
            @Parameter(description = "Max results (1 to 8)", example = "5")
            @RequestParam(name = "limit", defaultValue = "5") int limit
    ) {
        return ResponseEntity.ok(routingAndGeocodingService.searchPlacesInIndia(query, limit));
    }
}
