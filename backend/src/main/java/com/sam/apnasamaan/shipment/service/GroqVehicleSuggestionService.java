package com.sam.apnasamaan.shipment.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.shipment.config.GroqProperties;
import com.sam.apnasamaan.shipment.entity.PackageCategory;
import com.sam.apnasamaan.shipment.entity.SuggestionSource;
import com.sam.apnasamaan.shipment.entity.VehicleType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GroqVehicleSuggestionService implements VehicleSuggestionService {

    private final GroqProperties groqProperties;
    private final ObjectMapper objectMapper;
    private final RestClient groqClient;

    @Autowired
    public GroqVehicleSuggestionService(GroqProperties groqProperties, ObjectMapper objectMapper) {
        this(groqProperties, objectMapper, buildClient(groqProperties));
    }

    // Package-private constructor allows MockRestServiceServer testing without network calls
    GroqVehicleSuggestionService(
            GroqProperties groqProperties,
            ObjectMapper objectMapper,
            RestClient groqClient
    ) {
        this.groqProperties = groqProperties;
        this.objectMapper = objectMapper;
        this.groqClient = groqClient;
    }

    @Override
    public List<VehicleType> filterEligibleVehicles(
            List<VehicleType> activeVehiclesOrderedByFare,
            BigDecimal weightKg,
            int lengthCm,
            int widthCm,
            int heightCm
    ) {
        int[] pkgDims = sortedDimensions(lengthCm, widthCm, heightCm);

        List<VehicleType> eligible = activeVehiclesOrderedByFare.stream()
                .filter(vt -> vt.getMaxWeightKg().compareTo(weightKg) >= 0)
                .filter(vt -> fitsDimensions(pkgDims, vt))
                .toList();

        if (eligible.isEmpty()) {
            throw new BadRequestException(
                    "Package exceeds the maximum fleet capacity (up to 3500.00 kg and 420x190x190 cm on TRUCK)"
            );
        }
        return eligible;
    }

    @Override
    public VehicleAdvice suggestVehicle(
            List<VehicleType> eligibleVehicles,
            BigDecimal weightKg,
            int lengthCm,
            int widthCm,
            int heightCm,
            PackageCategory category,
            String description,
            boolean fragile
    ) {
        if (eligibleVehicles == null || eligibleVehicles.isEmpty()) {
            throw new BadRequestException("No eligible vehicle types available for this package");
        }

        // First eligible tier is the deterministic minimum fitting vehicle (catalog is ordered by baseFare ASC)
        VehicleType ruleMinimum = eligibleVehicles.get(0);

        if (!groqProperties.isConfigured()) {
            return buildRuleBasedAdvice(ruleMinimum, fragile);
        }

        try {
            Optional<GroqParsedChoice> aiChoice = queryGroqConfirmation(
                    eligibleVehicles, ruleMinimum, weightKg, lengthCm, widthCm, heightCm,
                    category, description, fragile
            );

            if (aiChoice.isPresent()) {
                GroqParsedChoice parsed = aiChoice.get();
                // Safety clamp: AI may confirm ruleMinimum or recommend a larger eligible tier, never a smaller one
                VehicleType clamped = eligibleVehicles.stream()
                        .filter(vt -> vt.getCode().equalsIgnoreCase(parsed.suggestedVehicleCode()))
                        .findFirst()
                        .orElse(ruleMinimum);

                String safeNote = sanitizeNote(parsed.reason(), clamped);
                log.info("Groq AI vehicle suggestion confirmed: vehicle={}, ruleMinimum={}, category={}, weightKg={}",
                        clamped.getCode(), ruleMinimum.getCode(), category, weightKg);
                return new VehicleAdvice(ruleMinimum, clamped.getCode(), SuggestionSource.GROQ_AI, safeNote);
            }
        } catch (Exception ex) {
            log.warn("Groq vehicle suggestion unavailable ({}); falling back to deterministic rule", ex.getMessage());
        }

        return buildRuleBasedAdvice(ruleMinimum, fragile);
    }

    private Optional<GroqParsedChoice> queryGroqConfirmation(
            List<VehicleType> eligibleVehicles,
            VehicleType ruleMinimum,
            BigDecimal weightKg,
            int lengthCm,
            int widthCm,
            int heightCm,
            PackageCategory category,
            String description,
            boolean fragile
    ) throws Exception {
        String allowedCodes = eligibleVehicles.stream()
                .map(VehicleType::getCode)
                .collect(Collectors.joining(", "));

        String systemPrompt = "You are a logistics vehicle advisor for Apna Samaan in India. "
                + "Confirm the safest vehicle code strictly from [" + allowedCodes + "]. "
                + "Never mention or calculate money/fares. "
                + "Respond ONLY with valid JSON: {\"suggestedVehicleCode\":\"CODE\",\"reason\":\"one short sentence under 160 chars\"}.";

        String safeDesc = (description == null || description.isBlank()) ? "General shipment" : description.trim();
        String userPrompt = String.format(
                "Category: %s | Description: %s | Weight: %s kg | Dimensions: %dx%dx%d cm | Fragile: %s | Rule minimum: %s",
                category, safeDesc, weightKg.toPlainString(), lengthCm, widthCm, heightCm, fragile, ruleMinimum.getCode()
        );

        Map<String, Object> payload = Map.of(
                "model", groqProperties.model(),
                "temperature", 0.1,
                "max_tokens", 120,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        String cleanApiKey = groqProperties.apiKey().trim().replace("\"", "").replace("'", "");
        GroqChatResponse response = groqClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cleanApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(GroqChatResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return Optional.empty();
        }
        GroqMessage message = response.choices().get(0).message();
        if (message == null || message.content() == null || message.content().isBlank()) {
            return Optional.empty();
        }

        GroqParsedChoice parsed = objectMapper.readValue(message.content().trim(), GroqParsedChoice.class);
        if (parsed.suggestedVehicleCode() == null || parsed.suggestedVehicleCode().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parsed);
    }

    private static VehicleAdvice buildRuleBasedAdvice(VehicleType ruleMinimum, boolean fragile) {
        String note = "Recommended " + ruleMinimum.getName() + " based on package weight and dimensions"
                + (fragile ? " (fragile handling flagged)." : ".");
        return new VehicleAdvice(ruleMinimum, ruleMinimum.getCode(), SuggestionSource.RULE_BASED, note);
    }

    private static String sanitizeNote(String rawReason, VehicleType selected) {
        if (rawReason == null || rawReason.isBlank()) {
            return "Confirmed " + selected.getName() + " for safe cargo handling.";
        }
        String trimmed = rawReason.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240);
    }

    private static boolean fitsDimensions(int[] sortedPkgDims, VehicleType vt) {
        // Sorting both 3D bounding boxes allows rotated loading (e.g., turning a carton 90 degrees)
        int[] vehicleDims = sortedDimensions(vt.getMaxLengthCm(), vt.getMaxWidthCm(), vt.getMaxHeightCm());
        return sortedPkgDims[0] <= vehicleDims[0]
                && sortedPkgDims[1] <= vehicleDims[1]
                && sortedPkgDims[2] <= vehicleDims[2];
    }

    private static int[] sortedDimensions(int a, int b, int c) {
        int[] dims = new int[]{a, b, c};
        Arrays.sort(dims);
        return dims;
    }

    private static RestClient buildClient(GroqProperties props) {
        Duration timeout = Duration.ofMillis(props.timeoutMillis());
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, "ApnaSamaan/1.0.0 (teamapnasamaan@gmail.com)")
                .requestFactory(requestFactory)
                .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GroqChatResponse(List<GroqChoice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GroqChoice(GroqMessage message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GroqMessage(String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GroqParsedChoice(String suggestedVehicleCode, String reason) {
    }
}
