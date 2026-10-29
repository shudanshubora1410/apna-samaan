package com.sam.apnasamaan.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.apnasamaan.auth.config.GoogleOAuthProperties;
import com.sam.apnasamaan.auth.dto.GoogleUserInfo;
import com.sam.apnasamaan.common.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Minimal, explicit OAuth 2.0 / OpenID Connect HTTP client for Google Sign-In.
 * Wraps the authorization URL builder, authorization-code token exchange, and userinfo lookup
 * without creating server-side HTTP sessions or logging tokens/secrets.
 */
@Slf4j
@Component
public class GoogleOAuthClient {

    private static final String OIDC_SCOPE = "openid email profile";

    private final GoogleOAuthProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public GoogleOAuthClient(GoogleOAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMs = properties.effectiveTimeoutMillis();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    public String buildAuthorizationUrl(String signedState) {
        return UriComponentsBuilder.fromUriString(properties.authorizationEndpoint())
                .queryParam("client_id", properties.clientId().trim())
                .queryParam("redirect_uri", properties.redirectUri().trim())
                .queryParam("response_type", "code")
                .queryParam("scope", OIDC_SCOPE)
                .queryParam("state", signedState)
                .queryParam("prompt", "select_account")
                .build()
                .encode()
                .toUriString();
    }

    public GoogleUserInfo exchangeCodeForUserInfo(String authorizationCode) {
        String googleAccessToken = exchangeAuthorizationCode(authorizationCode);
        return fetchUserInfo(googleAccessToken);
    }

    private String exchangeAuthorizationCode(String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", authorizationCode);
        form.add("client_id", properties.clientId().trim());
        form.add("client_secret", properties.clientSecret().trim());
        form.add("redirect_uri", properties.redirectUri().trim());
        form.add("grant_type", "authorization_code");

        try {
            String rawBody = restClient.post()
                    .uri(properties.tokenEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            if (rawBody == null || rawBody.isBlank()) {
                throw new UnauthorizedException("Empty token response from Google OAuth2");
            }

            JsonNode json = objectMapper.readTree(rawBody);
            String accessToken = json.path("access_token").asText("");
            if (accessToken.isBlank()) {
                throw new UnauthorizedException("Google OAuth2 response did not contain an access token");
            }
            return accessToken;
        } catch (RestClientException | IOException ex) {
            log.warn("Google OAuth2 authorization-code exchange failed: {}", ex.getClass().getSimpleName());
            throw new UnauthorizedException("Failed to verify Google authorization code");
        }
    }

    private GoogleUserInfo fetchUserInfo(String googleAccessToken) {
        try {
            String rawBody = restClient.get()
                    .uri(properties.userinfoEndpoint())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + googleAccessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);

            if (rawBody == null || rawBody.isBlank()) {
                throw new UnauthorizedException("Empty userinfo response from Google OAuth2");
            }

            JsonNode json = objectMapper.readTree(rawBody);
            String sub = json.path("sub").asText("").trim();
            String email = json.path("email").asText("").trim();
            boolean emailVerified = json.path("email_verified").asBoolean(false);
            String name = json.path("name").asText("").trim();

            if (sub.isBlank() || email.isBlank()) {
                throw new UnauthorizedException("Google profile is missing required identity claims");
            }

            return new GoogleUserInfo(sub, email, emailVerified, name);
        } catch (RestClientException | IOException ex) {
            log.warn("Google OAuth2 userinfo request failed: {}", ex.getClass().getSimpleName());
            throw new UnauthorizedException("Failed to retrieve Google account profile");
        }
    }
}
