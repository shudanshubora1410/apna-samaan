package com.sam.apnasamaan.auth;

import com.sam.apnasamaan.BaseIntegrationTest;
import com.sam.apnasamaan.auth.dto.AuthResponse;
import com.sam.apnasamaan.auth.dto.CustomerRegisterRequest;
import com.sam.apnasamaan.auth.dto.GoogleOAuthExchangeRequest;
import com.sam.apnasamaan.auth.dto.GoogleOAuthStatusResponse;
import com.sam.apnasamaan.auth.dto.LoginRequest;
import com.sam.apnasamaan.auth.entity.AuthProvider;
import com.sam.apnasamaan.auth.entity.Role;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.auth.security.AuthRateLimiter;
import com.sam.apnasamaan.auth.service.GoogleOAuthService;
import com.sam.apnasamaan.common.dto.ApiErrorResponse;
import com.sam.apnasamaan.customer.dto.CustomerProfileResponse;
import com.sam.apnasamaan.customer.dto.CustomerProfileUpdateRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleOAuthIntegrationTest extends BaseIntegrationTest {

    private static final HttpServer MOCK_GOOGLE_SERVER = startMockGoogleServer();
    private static final Map<String, String> CODE_TO_USERINFO_JSON = new ConcurrentHashMap<>();

    @LocalServerPort
    private int serverPort;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthRateLimiter rateLimiter;

    private RestTemplate noRedirectClient;
    private final List<String> createdEmails = new ArrayList<>();

    @DynamicPropertySource
    static void registerGoogleOAuthProperties(DynamicPropertyRegistry registry) {
        int port = MOCK_GOOGLE_SERVER.getAddress().getPort();
        registry.add("app.google-oauth.client-id", () -> "test-google-client-id.apps.googleusercontent.com");
        registry.add("app.google-oauth.client-secret", () -> "test-google-client-secret-" + UUID.randomUUID());
        registry.add("app.google-oauth.redirect-uri", () -> "http://localhost:8080/api/v1/auth/google/callback");
        registry.add("app.google-oauth.frontend-base-url", () -> "http://localhost:8080");
        registry.add("app.google-oauth.authorization-endpoint", () -> "http://127.0.0.1:" + port + "/o/oauth2/v2/auth");
        registry.add("app.google-oauth.token-endpoint", () -> "http://127.0.0.1:" + port + "/token");
        registry.add("app.google-oauth.userinfo-endpoint", () -> "http://127.0.0.1:" + port + "/userinfo");
    }

    @AfterAll
    static void stopServer() {
        MOCK_GOOGLE_SERVER.stop(0);
    }

    @BeforeEach
    void setUp() {
        rateLimiter.clearAll();
        CODE_TO_USERINFO_JSON.clear();

        CloseableHttpClient httpClient = HttpClients.custom()
                .disableRedirectHandling()
                .build();
        noRedirectClient = new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }

    @AfterEach
    void cleanUp() {
        for (String email : createdEmails) {
            userRepository.findByEmailIgnoreCase(email).ifPresent(userRepository::delete);
        }
        createdEmails.clear();
        rateLimiter.clearAll();
    }

    @Test
    void statusAndFrontendPages_exposeCustomerGoogleSignIn() {
        ResponseEntity<GoogleOAuthStatusResponse> status = restTemplate.getForEntity(
                "/api/v1/auth/google/status",
                GoogleOAuthStatusResponse.class
        );
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).isNotNull();
        assertThat(status.getBody().enabled()).isTrue();
        assertThat(status.getBody().authorizationPath()).isEqualTo("/api/v1/auth/google/authorize");

        ResponseEntity<String> loginHtml = restTemplate.getForEntity("/login.html", String.class);
        assertThat(loginHtml.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginHtml.getBody()).contains("data-google-auth-btn", "Continue with Google");

        ResponseEntity<String> registerHtml = restTemplate.getForEntity("/register-customer.html", String.class);
        assertThat(registerHtml.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(registerHtml.getBody()).contains("data-google-auth-btn", "Sign Up with Google");
    }

    @Test
    void newGoogleCustomer_canCompleteOAuthFlowUpdatePhoneAndCannotReplayTicket() {
        String googleEmail = "google.shipper." + UUID.randomUUID().toString().substring(0, 8) + "@apnasamaan.test";
        String googleSub = "google-sub-" + UUID.randomUUID();
        createdEmails.add(googleEmail);

        // 1. Start authorization redirect and capture signed state + HttpOnly cookie nonce
        ResponseEntity<Void> authRes = noRedirectClient.getForEntity(
                baseUrl() + "/api/v1/auth/google/authorize?next=customer.html",
                Void.class
        );
        assertThat(authRes.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        URI googleRedirect = authRes.getHeaders().getLocation();
        assertThat(googleRedirect).isNotNull();

        String stateParam = UriComponentsBuilder.fromUri(googleRedirect).build().getQueryParams().getFirst("state");
        String cookieNonce = extractCookieValue(authRes.getHeaders(), GoogleOAuthService.STATE_COOKIE_NAME);
        assertThat(stateParam).isNotBlank();
        assertThat(cookieNonce).isNotBlank();

        // 2. Register stubbed Google response for code and hit callback
        String authCode = "mock-code-" + UUID.randomUUID();
        CODE_TO_USERINFO_JSON.put(authCode, """
                {
                  "sub": "%s",
                  "email": "%s",
                  "email_verified": true,
                  "name": "Meera Nair"
                }
                """.formatted(googleSub, googleEmail));

        HttpHeaders callbackHeaders = new HttpHeaders();
        callbackHeaders.add(HttpHeaders.COOKIE, GoogleOAuthService.STATE_COOKIE_NAME + "=" + cookieNonce);
        URI callbackUri = UriComponentsBuilder.fromUriString(baseUrl() + "/api/v1/auth/google/callback")
                .queryParam("code", authCode)
                .queryParam("state", stateParam)
                .build()
                .encode()
                .toUri();

        ResponseEntity<Void> callbackRes = noRedirectClient.exchange(
                callbackUri,
                HttpMethod.GET,
                new HttpEntity<>(callbackHeaders),
                Void.class
        );
        assertThat(callbackRes.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        URI loginRedirect = callbackRes.getHeaders().getLocation();
        assertThat(loginRedirect).isNotNull();
        var redirectParams = UriComponentsBuilder.fromUri(loginRedirect).build().getQueryParams();
        String oauthTicket = redirectParams.getFirst("oauth_ticket");
        assertThat(oauthTicket).isNotBlank();
        assertThat(redirectParams.getFirst("next")).isEqualTo("customer.html");

        // 3. Exchange single-use ticket for standard JWT access + refresh tokens
        ResponseEntity<AuthResponse> exchangeRes = restTemplate.postForEntity(
                "/api/v1/auth/google/exchange",
                new GoogleOAuthExchangeRequest(oauthTicket),
                AuthResponse.class
        );
        assertThat(exchangeRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthResponse tokens = exchangeRes.getBody();
        assertThat(tokens).isNotNull();
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.user().role()).isEqualTo(Role.CUSTOMER);
        assertThat(tokens.user().authProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(tokens.user().phone()).isNull();

        // 4. Replaying the ticket must return 401
        ResponseEntity<ApiErrorResponse> replayRes = restTemplate.postForEntity(
                "/api/v1/auth/google/exchange",
                new GoogleOAuthExchangeRequest(oauthTicket),
                ApiErrorResponse.class
        );
        assertThat(replayRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 5. Password login on a Google-only account returns clean 401 (no NPE)
        ResponseEntity<ApiErrorResponse> pwdLoginRes = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(googleEmail, "SomeRandomPassword123"),
                ApiErrorResponse.class
        );
        assertThat(pwdLoginRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 6. Customer profile reads cleanly with googleLinked=true and allows adding a 10-digit mobile number
        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth(tokens.accessToken());

        ResponseEntity<CustomerProfileResponse> profileBefore = restTemplate.exchange(
                "/api/v1/customer/me",
                HttpMethod.GET,
                new HttpEntity<>(bearer),
                CustomerProfileResponse.class
        );
        assertThat(profileBefore.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profileBefore.getBody()).isNotNull();
        assertThat(profileBefore.getBody().googleLinked()).isTrue();
        assertThat(profileBefore.getBody().phone()).isNull();

        ResponseEntity<CustomerProfileResponse> profileUpdated = restTemplate.exchange(
                "/api/v1/customer/me",
                HttpMethod.PUT,
                new HttpEntity<>(new CustomerProfileUpdateRequest("Meera Nair", "9876504321"), bearer),
                CustomerProfileResponse.class
        );
        assertThat(profileUpdated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profileUpdated.getBody()).isNotNull();
        assertThat(profileUpdated.getBody().phone()).isEqualTo("9876504321");
    }

    @Test
    void existingCustomerIsLinkedSafely_whileAdminEmailIsStrictlyBlocked() {
        // A. Existing local CUSTOMER signs in with Google -> links googleSub to same userId & preserves local login
        String existingCustomerEmail = "local.shipper." + UUID.randomUUID().toString().substring(0, 8) + "@apnasamaan.test";
        createdEmails.add(existingCustomerEmail);

        ResponseEntity<AuthResponse> localReg = restTemplate.postForEntity(
                "/api/v1/auth/register/customer",
                new CustomerRegisterRequest("Karan Verma", existingCustomerEmail, "9812345678", "LocalPass#123"),
                AuthResponse.class
        );
        assertThat(localReg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long originalUserId = localReg.getBody().user().id();

        URI customerCallbackRedirect = runAuthorizeAndCallback(
                existingCustomerEmail,
                "google-sub-link-" + UUID.randomUUID(),
                "Karan Verma"
        );
        String ticket = UriComponentsBuilder.fromUri(customerCallbackRedirect).build().getQueryParams().getFirst("oauth_ticket");
        assertThat(ticket).isNotBlank();

        ResponseEntity<AuthResponse> linkedLogin = restTemplate.postForEntity(
                "/api/v1/auth/google/exchange",
                new GoogleOAuthExchangeRequest(ticket),
                AuthResponse.class
        );
        assertThat(linkedLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(linkedLogin.getBody().user().id()).isEqualTo(originalUserId);
        assertThat(linkedLogin.getBody().user().phone()).isEqualTo("9812345678");

        // Local email+password login still works for the linked customer
        ResponseEntity<AuthResponse> passwordStillWorks = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(existingCustomerEmail, "LocalPass#123"),
                AuthResponse.class
        );
        assertThat(passwordStillWorks.getStatusCode()).isEqualTo(HttpStatus.OK);

        // B. Bootstrapped ADMIN email tries Google login -> strictly blocked with role_not_customer
        URI adminCallbackRedirect = runAuthorizeAndCallback(
                TEST_ADMIN_EMAIL,
                "google-sub-admin-attempt-" + UUID.randomUUID(),
                "Admin User"
        );
        String adminError = UriComponentsBuilder.fromUri(adminCallbackRedirect).build().getQueryParams().getFirst("oauth_error");
        assertThat(adminError).isEqualTo("role_not_customer");
        assertThat(userRepository.findByEmailIgnoreCase(TEST_ADMIN_EMAIL).orElseThrow().getGoogleSub()).isNull();
    }

    private URI runAuthorizeAndCallback(String email, String sub, String name) {
        ResponseEntity<Void> authRes = noRedirectClient.getForEntity(
                baseUrl() + "/api/v1/auth/google/authorize",
                Void.class
        );
        String stateParam = UriComponentsBuilder.fromUri(authRes.getHeaders().getLocation())
                .build()
                .getQueryParams()
                .getFirst("state");
        String cookieNonce = extractCookieValue(authRes.getHeaders(), GoogleOAuthService.STATE_COOKIE_NAME);

        String authCode = "mock-code-" + UUID.randomUUID();
        CODE_TO_USERINFO_JSON.put(authCode, """
                {
                  "sub": "%s",
                  "email": "%s",
                  "email_verified": true,
                  "name": "%s"
                }
                """.formatted(sub, email, name));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, GoogleOAuthService.STATE_COOKIE_NAME + "=" + cookieNonce);
        URI callbackUri = UriComponentsBuilder.fromUriString(baseUrl() + "/api/v1/auth/google/callback")
                .queryParam("code", authCode)
                .queryParam("state", stateParam)
                .build()
                .encode()
                .toUri();

        ResponseEntity<Void> callbackRes = noRedirectClient.exchange(
                callbackUri,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Void.class
        );
        assertThat(callbackRes.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        return callbackRes.getHeaders().getLocation();
    }

    private String baseUrl() {
        return "http://localhost:" + serverPort;
    }

    private static String extractCookieValue(HttpHeaders headers, String cookieName) {
        List<String> cookies = headers.get(HttpHeaders.SET_COOKIE);
        if (cookies == null) {
            return null;
        }
        String prefix = cookieName + "=";
        for (String cookie : cookies) {
            if (cookie.startsWith(prefix)) {
                int semi = cookie.indexOf(';');
                return semi > 0 ? cookie.substring(prefix.length(), semi) : cookie.substring(prefix.length());
            }
        }
        return null;
    }

    private static HttpServer startMockGoogleServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

            server.createContext("/token", exchange -> {
                String formBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String code = UriComponentsBuilder.newInstance()
                        .query(formBody)
                        .build()
                        .getQueryParams()
                        .getFirst("code");

                if (code == null || !CODE_TO_USERINFO_JSON.containsKey(code)) {
                    writeJson(exchange, 400, "{\"error\":\"invalid_grant\"}");
                    return;
                }
                writeJson(exchange, 200, "{\"access_token\":\"stub-token-" + code + "\",\"token_type\":\"Bearer\"}");
            });

            server.createContext("/userinfo", exchange -> {
                String authHeader = exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                String prefix = "Bearer stub-token-";
                if (authHeader == null || !authHeader.startsWith(prefix)) {
                    writeJson(exchange, 401, "{\"error\":\"invalid_token\"}");
                    return;
                }
                String code = authHeader.substring(prefix.length());
                String payload = CODE_TO_USERINFO_JSON.get(code);
                if (payload == null) {
                    writeJson(exchange, 401, "{\"error\":\"invalid_token\"}");
                    return;
                }
                writeJson(exchange, 200, payload);
            });

            server.start();
            return server;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to start mock Google OAuth2 HTTP server", ex);
        }
    }

    private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
