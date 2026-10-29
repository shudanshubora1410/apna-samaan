package com.sam.apnasamaan.shipment.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.common.util.GeoDistanceUtil;
import com.sam.apnasamaan.shipment.config.MapsProperties;
import com.sam.apnasamaan.shipment.dto.PlaceSuggestionResponse;
import com.sam.apnasamaan.shipment.entity.RouteSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class OsmRoutingAndGeocodingService implements RoutingAndGeocodingService {

    private static final double MIN_STRAIGHT_LINE_DISTANCE_KM = 0.05;
    private static final BigDecimal MIN_BILLABLE_ROUTE_KM = new BigDecimal("0.50");
    private static final int MIN_DURATION_MINUTES = 5;
    private static final int MAX_GEOCODE_LIMIT = 8;

    private final MapsProperties mapsProperties;
    private final RestClient osrmClient;
    private final RestClient nominatimClient;

    @Autowired
    public OsmRoutingAndGeocodingService(MapsProperties mapsProperties) {
        this(
                mapsProperties,
                buildClient(mapsProperties.osrmBaseUrl(), mapsProperties.userAgent(), mapsProperties.timeoutMillis()),
                buildClient(mapsProperties.nominatimBaseUrl(), mapsProperties.userAgent(), mapsProperties.timeoutMillis())
        );
    }

    // Package-private constructor allows unit tests to inject MockRestServiceServer-backed RestClients
    OsmRoutingAndGeocodingService(
            MapsProperties mapsProperties,
            RestClient osrmClient,
            RestClient nominatimClient
    ) {
        this.mapsProperties = mapsProperties;
        this.osrmClient = osrmClient;
        this.nominatimClient = nominatimClient;
    }

    @Override
    public RouteEstimate calculateRoute(double pickupLat, double pickupLng, double dropLat, double dropLng) {
        double straightLineKm = GeoDistanceUtil.haversineKm(pickupLat, pickupLng, dropLat, dropLng);
        if (straightLineKm < MIN_STRAIGHT_LINE_DISTANCE_KM) {
            throw new BadRequestException("Pickup and dropoff locations must be at least 50 meters apart");
        }

        try {
            // OSRM expects coordinates in {longitude},{latitude} order, not {latitude},{longitude}
            String coordinatesPath = String.format(
                    Locale.ROOT,
                    "/route/v1/driving/%.6f,%.6f;%.6f,%.6f",
                    pickupLng, pickupLat, dropLng, dropLat
            );

            OsrmRouteResponse response = osrmClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(coordinatesPath)
                            .queryParam("overview", "false")
                            .build())
                    .retrieve()
                    .body(OsrmRouteResponse.class);

            if (response != null
                    && "Ok".equalsIgnoreCase(response.code())
                    && response.routes() != null
                    && !response.routes().isEmpty()
                    && response.routes().get(0).distance() > 0) {

                OsrmRoute primary = response.routes().get(0);
                BigDecimal rawKm = BigDecimal.valueOf(primary.distance())
                        .divide(new BigDecimal("1000"), 2, RoundingMode.HALF_UP);
                BigDecimal roadKm = rawKm.max(MIN_BILLABLE_ROUTE_KM);
                int durationMinutes = Math.max(MIN_DURATION_MINUTES, (int) Math.ceil(primary.duration() / 60.0));
                return new RouteEstimate(roadKm, durationMinutes, RouteSource.OSRM);
            }
        } catch (Exception ex) {
            log.warn("OSRM route lookup unavailable ({}); using Haversine road-factor fallback", ex.getMessage());
        }

        // Resilient deterministic fallback when public OSRM is unreachable or rate-limited
        BigDecimal fallbackKm = BigDecimal.valueOf(straightLineKm * mapsProperties.haversineRoadFactor())
                .setScale(2, RoundingMode.HALF_UP)
                .max(MIN_BILLABLE_ROUTE_KM);
        int fallbackMinutes = Math.max(
                MIN_DURATION_MINUTES,
                (int) Math.ceil((fallbackKm.doubleValue() / mapsProperties.averageSpeedKmh()) * 60.0)
        );
        return new RouteEstimate(fallbackKm, fallbackMinutes, RouteSource.HAVERSINE_FALLBACK);
    }

    @Override
    public List<PlaceSuggestionResponse> searchPlacesInIndia(String query, int limit) {
        if (query == null || query.trim().length() < 2) {
            throw new BadRequestException("Search query must be at least 2 characters");
        }
        int safeLimit = Math.min(Math.max(limit, 1), MAX_GEOCODE_LIMIT);
        String trimmedQuery = query.trim();

        try {
            List<NominatimPlace> results = nominatimClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search")
                            .queryParam("format", "jsonv2")
                            .queryParam("countrycodes", "in")
                            .queryParam("addressdetails", 1)
                            .queryParam("limit", safeLimit)
                            .queryParam("q", trimmedQuery)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });

            if (results == null) {
                return List.of();
            }

            return results.stream()
                    .filter(p -> p.lat() != null && p.lon() != null && p.displayName() != null)
                    .map(this::toSuggestion)
                    .toList();
        } catch (Exception ex) {
            log.warn("Nominatim geocode search unavailable for query '{}': {}", trimmedQuery, ex.getMessage());
            return List.of();
        }
    }

    private PlaceSuggestionResponse toSuggestion(NominatimPlace place) {
        String city = null;
        String state = null;
        if (place.address() != null) {
            city = firstNonBlank(
                    place.address().city(),
                    place.address().town(),
                    place.address().village(),
                    place.address().stateDistrict(),
                    place.address().county()
            );
            state = place.address().state();
        }
        return new PlaceSuggestionResponse(
                place.displayName(),
                city,
                state,
                Double.parseDouble(place.lat()),
                Double.parseDouble(place.lon())
        );
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return null;
    }

    private static RestClient buildClient(String baseUrl, String userAgent, int timeoutMillis) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OsrmRouteResponse(String code, List<OsrmRoute> routes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OsrmRoute(double distance, double duration) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NominatimPlace(
            @JsonProperty("display_name") String displayName,
            String lat,
            String lon,
            NominatimAddress address
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NominatimAddress(
            String city,
            String town,
            String village,
            @JsonProperty("state_district") String stateDistrict,
            String county,
            String state
    ) {
    }
}
