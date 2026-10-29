package com.sam.apnasamaan.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for Customer Google OAuth2 sign-in.
 * When clientId or clientSecret is blank or a CHANGE_ME placeholder, the backend starts cleanly
 * with Google OAuth2 disabled (`isConfigured() == false`).
 */
@ConfigurationProperties(prefix = "app.google-oauth")
public record GoogleOAuthProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String frontendBaseUrl,
        String authorizationEndpoint,
        String tokenEndpoint,
        String userinfoEndpoint,
        int ticketTtlSeconds,
        int timeoutMillis
) {

    public boolean isConfigured() {
        return isRealCredential(clientId) && isRealCredential(clientSecret);
    }

    public int effectiveTicketTtlSeconds() {
        return ticketTtlSeconds > 0 ? ticketTtlSeconds : 60;
    }

    public int effectiveTimeoutMillis() {
        return timeoutMillis > 0 ? timeoutMillis : 5000;
    }

    public String normalizedFrontendBaseUrl() {
        String base = (frontendBaseUrl == null || frontendBaseUrl.isBlank())
                ? "http://localhost:8080"
                : frontendBaseUrl.trim();
        return base.replaceAll("/+$", "");
    }

    private static boolean isRealCredential(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        return !trimmed.startsWith("CHANGE_ME");
    }
}
