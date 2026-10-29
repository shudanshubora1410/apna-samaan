package com.sam.apnasamaan.shipment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.shipment.config.GroqProperties;
import com.sam.apnasamaan.shipment.entity.PackageCategory;
import com.sam.apnasamaan.shipment.entity.SuggestionSource;
import com.sam.apnasamaan.shipment.entity.VehicleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GroqVehicleSuggestionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private List<VehicleType> catalog;

    @BeforeEach
    void setUp() {
        catalog = List.of(
                VehicleType.builder().id(1L).code("BIKE").name("Bike")
                        .maxWeightKg(new BigDecimal("20.00"))
                        .maxLengthCm(40).maxWidthCm(40).maxHeightCm(40)
                        .baseFare(new BigDecimal("40.00")).perKmRate(new BigDecimal("8.50")).build(),
                VehicleType.builder().id(2L).code("CARGO_AUTO").name("Cargo Auto")
                        .maxWeightKg(new BigDecimal("500.00"))
                        .maxLengthCm(170).maxWidthCm(120).maxHeightCm(120)
                        .baseFare(new BigDecimal("180.00")).perKmRate(new BigDecimal("18.00")).build(),
                VehicleType.builder().id(3L).code("MINI_TRUCK").name("Mini Truck")
                        .maxWeightKg(new BigDecimal("1000.00"))
                        .maxLengthCm(220).maxWidthCm(150).maxHeightCm(150)
                        .baseFare(new BigDecimal("350.00")).perKmRate(new BigDecimal("26.00")).build(),
                VehicleType.builder().id(4L).code("TRUCK").name("Truck")
                        .maxWeightKg(new BigDecimal("3500.00"))
                        .maxLengthCm(420).maxWidthCm(190).maxHeightCm(190)
                        .baseFare(new BigDecimal("900.00")).perKmRate(new BigDecimal("45.00")).build()
        );
    }

    @Test
    void filterEligibleVehicles_supportsRotatedDimensions_andRejectsOverCapacity() {
        GroqProperties unconfigured = new GroqProperties("", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", 1500);
        GroqVehicleSuggestionService service = new GroqVehicleSuggestionService(unconfigured, objectMapper);

        // 100 x 160 x 80 cm rotated fits Cargo Auto (170 x 120 x 120 cm)
        List<VehicleType> eligible = service.filterEligibleVehicles(
                catalog, new BigDecimal("120.00"), 100, 160, 80
        );
        assertThat(eligible).extracting(VehicleType::getCode)
                .containsExactly("CARGO_AUTO", "MINI_TRUCK", "TRUCK");

        // Over 3500 kg fails cleanly
        assertThatThrownBy(() -> service.filterEligibleVehicles(
                catalog, new BigDecimal("4000.00"), 100, 100, 100
        )).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("maximum fleet capacity");
    }

    @Test
    void suggestVehicle_usesDeterministicRuleWhenGroqKeyIsUnsetOrPlaceholder() {
        GroqProperties placeholder = new GroqProperties(
                "CHANGE_ME_GROQ_API_KEY",
                "https://api.groq.com/openai/v1",
                "llama-3.3-70b-versatile",
                1500
        );
        GroqVehicleSuggestionService service = new GroqVehicleSuggestionService(placeholder, objectMapper);

        List<VehicleType> eligible = catalog.subList(1, 4); // CARGO_AUTO, MINI_TRUCK, TRUCK
        VehicleSuggestionService.VehicleAdvice advice = service.suggestVehicle(
                eligible, new BigDecimal("120.00"), 110, 80, 90,
                PackageCategory.HOUSEHOLD, "Washing machine", true
        );

        assertThat(advice.source()).isEqualTo(SuggestionSource.RULE_BASED);
        assertThat(advice.ruleBasedMinimumVehicle().getCode()).isEqualTo("CARGO_AUTO");
        assertThat(advice.aiSuggestedVehicleCode()).isEqualTo("CARGO_AUTO");
        assertThat(advice.note()).contains("Cargo Auto");
    }

    @Test
    void suggestVehicle_acceptsGroqUpgrade_andClampsUndersizedHallucinationToRuleMinimum() {
        GroqProperties configured = new GroqProperties(
                "gsk_test_dummy_key",
                "https://api.groq.com/openai/v1",
                "llama-3.3-70b-versatile",
                1500
        );

        RestClient.Builder builder = RestClient.builder().baseUrl(configured.baseUrl());
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        GroqVehicleSuggestionService service = new GroqVehicleSuggestionService(configured, objectMapper, builder.build());

        List<VehicleType> eligible = catalog.subList(1, 4); // CARGO_AUTO, MINI_TRUCK, TRUCK

        // Case 1: Groq recommends larger MINI_TRUCK for fragile glass -> accepted
        mockServer.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "content": "{\\"suggestedVehicleCode\\":\\"MINI_TRUCK\\",\\"reason\\":\\"Closed deck protects fragile glass panels.\\"}"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        VehicleSuggestionService.VehicleAdvice upgraded = service.suggestVehicle(
                eligible, new BigDecimal("250.00"), 150, 100, 100,
                PackageCategory.FRAGILE, "Tempered glass panels", true
        );
        assertThat(upgraded.source()).isEqualTo(SuggestionSource.GROQ_AI);
        assertThat(upgraded.ruleBasedMinimumVehicle().getCode()).isEqualTo("CARGO_AUTO");
        assertThat(upgraded.aiSuggestedVehicleCode()).isEqualTo("MINI_TRUCK");
        mockServer.verify();

        // Case 2: Groq hallucinates undersized BIKE -> clamped back up to CARGO_AUTO
        mockServer.reset();
        mockServer.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "content": "{\\"suggestedVehicleCode\\":\\"BIKE\\",\\"reason\\":\\"Fast two-wheeler.\\"}"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        VehicleSuggestionService.VehicleAdvice clamped = service.suggestVehicle(
                eligible, new BigDecimal("120.00"), 110, 80, 90,
                PackageCategory.HOUSEHOLD, "Washing machine", false
        );
        assertThat(clamped.aiSuggestedVehicleCode()).isEqualTo("CARGO_AUTO");
        mockServer.verify();

        // Case 3: Groq returns 503 -> graceful fallback to RULE_BASED
        mockServer.reset();
        mockServer.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        VehicleSuggestionService.VehicleAdvice fallback = service.suggestVehicle(
                eligible, new BigDecimal("120.00"), 110, 80, 90,
                PackageCategory.HOUSEHOLD, "Washing machine", false
        );
        assertThat(fallback.source()).isEqualTo(SuggestionSource.RULE_BASED);
        assertThat(fallback.aiSuggestedVehicleCode()).isEqualTo("CARGO_AUTO");
        mockServer.verify();
    }
}
