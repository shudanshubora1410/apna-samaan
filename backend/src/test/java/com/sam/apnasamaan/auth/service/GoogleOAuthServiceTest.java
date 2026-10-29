package com.sam.apnasamaan.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.apnasamaan.auth.config.GoogleOAuthProperties;
import com.sam.apnasamaan.auth.config.JwtProperties;
import com.sam.apnasamaan.auth.dto.AuthResponse;
import com.sam.apnasamaan.auth.dto.GoogleOAuthExchangeRequest;
import com.sam.apnasamaan.auth.dto.GoogleOAuthStatusResponse;
import com.sam.apnasamaan.auth.dto.GoogleUserInfo;
import com.sam.apnasamaan.auth.dto.UserSummaryResponse;
import com.sam.apnasamaan.auth.entity.AuthProvider;
import com.sam.apnasamaan.auth.entity.Role;
import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.common.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleOAuthServiceTest {

    private static final String TEST_SECRET = UUID.randomUUID().toString() + UUID.randomUUID();

    @Mock
    private GoogleOAuthClient googleOAuthClient;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthService authService;

    private GoogleOAuthService configuredService;
    private JwtProperties jwtProperties;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties(TEST_SECRET, 15, 7);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionStatus txStatus = new SimpleTransactionStatus();
        lenient().when(txManager.getTransaction(any())).thenReturn(txStatus);
        transactionTemplate = new TransactionTemplate(txManager);

        GoogleOAuthProperties props = new GoogleOAuthProperties(
                "real-google-client-id.apps.googleusercontent.com",
                "real-google-client-secret",
                "http://localhost:8080/api/v1/auth/google/callback",
                "http://localhost:8080",
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "https://openidconnect.googleapis.com/v1/userinfo",
                60,
                3000
        );

        configuredService = new GoogleOAuthService(
                props,
                jwtProperties,
                googleOAuthClient,
                userRepository,
                authService,
                transactionTemplate
        );
    }

    @Test
    void placeholderCredentials_reportDisabledAndRedirectCleanly() {
        GoogleOAuthProperties placeholderProps = new GoogleOAuthProperties(
                "CHANGE_ME_GOOGLE_OAUTH_CLIENT_ID",
                "CHANGE_ME_GOOGLE_OAUTH_CLIENT_SECRET",
                "http://localhost:8080/api/v1/auth/google/callback",
                "http://localhost:8080/",
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "https://openidconnect.googleapis.com/v1/userinfo",
                60,
                3000
        );

        GoogleOAuthClient realClient = new GoogleOAuthClient(placeholderProps, new ObjectMapper());
        GoogleOAuthService unconfiguredService = new GoogleOAuthService(
                placeholderProps,
                jwtProperties,
                realClient,
                userRepository,
                authService,
                transactionTemplate
        );

        GoogleOAuthStatusResponse status = unconfiguredService.getStatus();
        assertThat(status.enabled()).isFalse();
        assertThat(status.authorizationPath()).isEqualTo("/api/v1/auth/google/authorize");

        GoogleOAuthService.AuthorizationRedirect redirect = unconfiguredService.startAuthorization("customer.html");
        assertThat(redirect.configured()).isFalse();
        assertThat(redirect.cookieNonce()).isNull();
        assertThat(redirect.redirectUrl()).isEqualTo("http://localhost:8080/login.html?oauth_error=not_configured");
    }

    @Test
    void callback_rejectsMissingCookieTamperedStateAndProviderErrors() {
        when(googleOAuthClient.buildAuthorizationUrl(any())).thenAnswer(inv ->
                "https://accounts.google.com/o/oauth2/v2/auth?state=" + inv.getArgument(0, String.class));

        GoogleOAuthService.AuthorizationRedirect started = configuredService.startAuthorization("https://evil.example");
        String signedState = UriComponentsBuilder.fromUriString(started.redirectUrl())
                .build()
                .getQueryParams()
                .getFirst("state");

        // User cancelled on Google consent screen
        assertThat(configuredService.handleCallback(null, signedState, "access_denied", started.cookieNonce()))
                .endsWith("?oauth_error=access_denied");

        // Missing or mismatched CSRF cookie nonce
        assertThat(configuredService.handleCallback("code-1", signedState, null, null))
                .endsWith("?oauth_error=state_invalid");
        assertThat(configuredService.handleCallback("code-1", signedState, null, "wrong-cookie-nonce"))
                .endsWith("?oauth_error=state_invalid");

        // Tampered state JWT
        assertThat(configuredService.handleCallback("code-1", signedState + "tamper", null, started.cookieNonce()))
                .endsWith("?oauth_error=state_invalid");

        // Unverified Google email must never be trusted
        when(googleOAuthClient.exchangeCodeForUserInfo("code-unverified"))
                .thenReturn(new GoogleUserInfo("sub-1", "unverified@example.com", false, "Unverified"));
        assertThat(configuredService.handleCallback("code-unverified", signedState, null, started.cookieNonce()))
                .endsWith("?oauth_error=email_unverified");
        verify(userRepository, never()).save(any());
    }

    @Test
    void callback_blocksDriverAdminDisabledAndConflictingAccounts() {
        when(googleOAuthClient.buildAuthorizationUrl(any())).thenAnswer(inv ->
                "https://accounts.google.com/o/oauth2/v2/auth?state=" + inv.getArgument(0, String.class));

        GoogleOAuthService.AuthorizationRedirect started = configuredService.startAuthorization("customer.html");
        String signedState = UriComponentsBuilder.fromUriString(started.redirectUrl())
                .build()
                .getQueryParams()
                .getFirst("state");

        // 1. Existing DRIVER account with same email -> blocked
        User driver = User.builder()
                .id(10L)
                .name("Raju Driver")
                .email("driver@apnasamaan.test")
                .role(Role.DRIVER)
                .enabled(true)
                .build();
        when(googleOAuthClient.exchangeCodeForUserInfo("code-driver"))
                .thenReturn(new GoogleUserInfo("sub-driver", "driver@apnasamaan.test", true, "Raju Driver"));
        when(userRepository.findByGoogleSub("sub-driver")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("driver@apnasamaan.test")).thenReturn(Optional.of(driver));

        assertThat(configuredService.handleCallback("code-driver", signedState, null, started.cookieNonce()))
                .endsWith("?oauth_error=role_not_customer");

        // 2. Deactivated CUSTOMER -> blocked
        User disabledCustomer = User.builder()
                .id(11L)
                .name("Disabled User")
                .email("disabled@apnasamaan.test")
                .role(Role.CUSTOMER)
                .enabled(false)
                .build();
        when(googleOAuthClient.exchangeCodeForUserInfo("code-disabled"))
                .thenReturn(new GoogleUserInfo("sub-disabled", "disabled@apnasamaan.test", true, "Disabled User"));
        when(userRepository.findByGoogleSub("sub-disabled")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("disabled@apnasamaan.test")).thenReturn(Optional.of(disabledCustomer));

        assertThat(configuredService.handleCallback("code-disabled", signedState, null, started.cookieNonce()))
                .endsWith("?oauth_error=account_disabled");

        // 3. Existing CUSTOMER already linked to a different googleSub -> conflict
        User conflictCustomer = User.builder()
                .id(12L)
                .name("Linked Customer")
                .email("linked@apnasamaan.test")
                .role(Role.CUSTOMER)
                .googleSub("original-google-sub")
                .enabled(true)
                .build();
        when(googleOAuthClient.exchangeCodeForUserInfo("code-conflict"))
                .thenReturn(new GoogleUserInfo("different-google-sub", "linked@apnasamaan.test", true, "Linked Customer"));
        when(userRepository.findByGoogleSub("different-google-sub")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("linked@apnasamaan.test")).thenReturn(Optional.of(conflictCustomer));

        assertThat(configuredService.handleCallback("code-conflict", signedState, null, started.cookieNonce()))
                .endsWith("?oauth_error=account_conflict");
    }

    @Test
    void ticketExchange_isStrictlySingleUse() {
        when(googleOAuthClient.buildAuthorizationUrl(any())).thenAnswer(inv ->
                "https://accounts.google.com/o/oauth2/v2/auth?state=" + inv.getArgument(0, String.class));

        GoogleOAuthService.AuthorizationRedirect started = configuredService.startAuthorization("customer.html");
        String signedState = UriComponentsBuilder.fromUriString(started.redirectUrl())
                .build()
                .getQueryParams()
                .getFirst("state");

        User savedCustomer = User.builder()
                .id(42L)
                .name("Priya Shipper")
                .email("priya@apnasamaan.test")
                .role(Role.CUSTOMER)
                .authProvider(AuthProvider.GOOGLE)
                .googleSub("google-sub-42")
                .enabled(true)
                .build();

        when(googleOAuthClient.exchangeCodeForUserInfo("code-ok"))
                .thenReturn(new GoogleUserInfo("google-sub-42", "priya@apnasamaan.test", true, "Priya Shipper"));
        when(userRepository.findByGoogleSub("google-sub-42")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("priya@apnasamaan.test")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenReturn(savedCustomer);
        when(userRepository.findById(42L)).thenReturn(Optional.of(savedCustomer));

        AuthResponse mockedTokens = new AuthResponse(
                "jwt-access",
                "raw-refresh",
                "Bearer",
                900L,
                new UserSummaryResponse(42L, "Priya Shipper", "priya@apnasamaan.test", null,
                        Role.CUSTOMER, AuthProvider.GOOGLE, true, Instant.now())
        );
        when(authService.issueTokensForUser(savedCustomer)).thenReturn(mockedTokens);

        String redirect = configuredService.handleCallback("code-ok", signedState, null, started.cookieNonce());
        String ticket = UriComponentsBuilder.fromUriString(redirect)
                .build()
                .getQueryParams()
                .getFirst("oauth_ticket");
        assertThat(ticket).isNotBlank();

        AuthResponse firstExchange = configuredService.exchangeTicket(new GoogleOAuthExchangeRequest(ticket));
        assertThat(firstExchange.accessToken()).isEqualTo("jwt-access");

        // Replaying the consumed ticket must immediately fail with 401
        assertThatThrownBy(() -> configuredService.exchangeTicket(new GoogleOAuthExchangeRequest(ticket)))
                .isInstanceOf(UnauthorizedException.class);
    }
}
