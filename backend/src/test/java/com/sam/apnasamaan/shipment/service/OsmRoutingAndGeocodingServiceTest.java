package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.shipment.config.MapsProperties;
import com.sam.apnasamaan.shipment.dto.PlaceSuggestionResponse;
import com.sam.apnasamaan.shipment.entity.RouteSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OsmRoutingAndGeocodingServiceTest {

    private MapsProperties mapsProperties;
    private MockRestServiceServer osrmServer;
    private MockRestServiceServer nominatimServer;
    private OsmRoutingAndGeocodingService service;

    @BeforeEach
    void setUp() {
        mapsProperties = new MapsProperties(
                "https://nominatim.openstreetmap.org",
                "https://router.project-osrm.org",
                "ApnaSamaanTest/0.4.0",
                1500,
                1.30,
                28.0
        );

        RestClient.Builder osrmBuilder = RestClient.builder().baseUrl(mapsProperties.osrmBaseUrl());
        osrmServer = MockRestServiceServer.bindTo(osrmBuilder).build();

        RestClient.Builder nominatimBuilder = RestClient.builder().baseUrl(mapsProperties.nominatimBaseUrl());
        nominatimServer = MockRestServiceServer.bindTo(nominatimBuilder).build();

        service = new OsmRoutingAndGeocodingService(mapsProperties, osrmBuilder.build(), nominatimBuilder.build());
    }

    @Test
    void calculateRoute_parsesOsrmLngLatResponseWhenAvailable() {
        // Connaught Place (28.6315, 77.2167) -> Cyber Hub (28.4950, 77.0895)
        osrmServer.expect(requestTo(containsString("/route/v1/driving/77.216700,28.631500;77.089500,28.495000")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "code": "Ok",
                          "routes": [{
                            "distance": 26480.0,
                            "duration": 2310.0
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        RoutingAndGeocodingService.RouteEstimate estimate = service.calculateRoute(
                28.6315, 77.2167, 28.4950, 77.0895
        );

        assertThat(estimate.source()).isEqualTo(RouteSource.OSRM);
        assertThat(estimate.distanceKm()).isEqualByComparingTo("26.48");
        assertThat(estimate.estimatedDurationMinutes()).isEqualTo(39);
        osrmServer.verify();
    }

    @Test
    void calculateRoute_fallsBackToHaversineWhenOsrmFails_andRejectsSamePin() {
        osrmServer.expect(requestTo(containsString("/route/v1/driving/")))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        RoutingAndGeocodingService.RouteEstimate fallback = service.calculateRoute(
                28.6315, 77.2167, 28.4950, 77.0895
        );

        assertThat(fallback.source()).isEqualTo(RouteSource.HAVERSINE_FALLBACK);
        assertThat(fallback.distanceKm()).isBetween(
                new java.math.BigDecimal("24.00"),
                new java.math.BigDecimal("27.00")
        );
        assertThat(fallback.estimatedDurationMinutes()).isGreaterThan(45);
        osrmServer.verify();

        // Identical pickup & dropoff (< 50m) must fail immediately with 400 BadRequestException
        assertThatThrownBy(() -> service.calculateRoute(28.6315, 77.2167, 28.6315, 77.2167))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at least 50 meters apart");
    }

    @Test
    void searchPlacesInIndia_parsesNominatimResults_andHandlesShortQuery() {
        nominatimServer.expect(requestTo(containsString("countrycodes=in")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {
                            "display_name": "Connaught Place, New Delhi, Delhi, 110001, India",
                            "lat": "28.6315",
                            "lon": "77.2167",
                            "address": {
                              "city": "New Delhi",
                              "state": "Delhi"
                            }
                          }
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<PlaceSuggestionResponse> suggestions = service.searchPlacesInIndia("Connaught Place", 5);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).city()).isEqualTo("New Delhi");
        assertThat(suggestions.get(0).state()).isEqualTo("Delhi");
        assertThat(suggestions.get(0).lat()).isEqualTo(28.6315);
        assertThat(suggestions.get(0).lng()).isEqualTo(77.2167);
        nominatimServer.verify();

        assertThatThrownBy(() -> service.searchPlacesInIndia("a", 5))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at least 2 characters");
    }
}
