package com.sam.apnasamaan.auth.service;

import com.sam.apnasamaan.auth.config.GoogleOAuthProperties;
import com.sam.apnasamaan.auth.config.JwtProperties;
import com.sam.apnasamaan.auth.dto.AuthResponse;
import com.sam.apnasamaan.auth.dto.GoogleOAuthExchangeRequest;
import com.sam.apnasamaan.auth.dto.GoogleOAuthStatusResponse;
import com.sam.apnasamaan.auth.dto.GoogleUserInfo;
import com.sam.apnasamaan.auth.entity.AuthProvider;
import com.sam.apnasamaan.auth.entity.Role;
import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.common.exception.ConflictException;
import com.sam.apnasamaan.common.exception.ForbiddenOperationException;
import com.sam.apnasamaan.common.exception.UnauthorizedException;
import com.sam.apnasamaan.common.util.TokenHashUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Stateless Customer Google OAuth2 orchestrator:
 * 1. Signs a short-lived CSRF `state` token paired with an HttpOnly nonce cookie (zero HttpSession).
 * 2. Exchanges the callback code with Google outside any DB transaction so Hikari connections stay free.
 * 3. Creates or links ONLY `CUSTOMER` accounts (`DRIVER` and `ADMIN` emails are strictly blocked).
 * 4. Issues a 60-second single-use handoff ticket so JWTs never appear in browser URL query strings.
 */
@Slf4j
@Service
public class GoogleOAuthService {

    public static final String AUTHORIZE_PATH = "/api/v1/auth/google/authorize";
    public static final String STATE_COOKIE_NAME = "AS_OAUTH_STATE";
    public static final int STATE_TTL_SECONDS = 600;

    private static final String STATE_SUBJECT = "google_oauth_state";
    private static final Pattern SAFE_NEXT_PAGE = Pattern.compile("^[a-zA-Z0-9\\-]+\\.html$");
    private static final int MAX_CACHED_TICKETS = 2048;

    private final GoogleOAuthProperties properties;
    private final GoogleOAuthClient googleOAuthClient;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final TransactionTemplate transactionTemplate;
    private final SecretKey stateSigningKey;

    private final Map<String, PendingOAuthTicket> ticketStore = new ConcurrentHashMap<>();

    public GoogleOAuthService(
            GoogleOAuthProperties properties,
            JwtProperties jwtProperties,
            GoogleOAuthClient googleOAuthClient,
            UserRepository userRepository,
            AuthService authService,
            TransactionTemplate transactionTemplate
    ) {
        this.properties = properties;
        this.googleOAuthClient = googleOAuthClient;
        this.userRepository = userRepository;
        this.authService = authService;
        this.transactionTemplate = transactionTemplate;
        // Domain-separated HMAC key so an OAuth state JWT is cryptographically distinct from Bearer access tokens
        byte[] keyBytes = (jwtProperties.secret() + ":google-oauth-state").getBytes(StandardCharsets.UTF_8);
        this.stateSigningKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public GoogleOAuthStatusResponse getStatus() {
        return new GoogleOAuthStatusResponse(properties.isConfigured(), AUTHORIZE_PATH);
    }

    public AuthorizationRedirect startAuthorization(String requestedNext) {
        if (!properties.isConfigured()) {
            return new AuthorizationRedirect(buildLoginErrorRedirect("not_configured"), null, false);
        }

        String nonce = TokenHashUtil.generateRandomToken();
        String safeNext = sanitizeNextPage(requestedNext);
        String signedState = signStateToken(nonce, safeNext);
        String googleUrl = googleOAuthClient.buildAuthorizationUrl(signedState);

        return new AuthorizationRedirect(googleUrl, nonce, true);
    }

    public String handleCallback(String code, String state, String providerError, String cookieNonce) {
        if (!properties.isConfigured()) {
            return buildLoginErrorRedirect("not_configured");
        }

        if (providerError != null && !providerError.isBlank()) {
            String errorCode = "access_denied".equalsIgnoreCase(providerError.trim())
                    ? "access_denied"
                    : "provider_error";
            return buildLoginErrorRedirect(errorCode);
        }

        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            return buildLoginErrorRedirect("state_invalid");
        }

        VerifiedState verifiedState;
        try {
            verifiedState = verifyStateToken(state, cookieNonce);
        } catch (UnauthorizedException ex) {
            log.warn("Rejected Google OAuth2 callback due to invalid state/cookie: {}", ex.getMessage());
            return buildLoginErrorRedirect("state_invalid");
        }

        GoogleUserInfo userInfo;
        try {
            // Network call to Google intentionally runs outside DB transactions
            userInfo = googleOAuthClient.exchangeCodeForUserInfo(code.trim());
        } catch (UnauthorizedException ex) {
            return buildLoginErrorRedirect("provider_error");
        }

        if (!userInfo.emailVerified()) {
            log.warn("Rejected Google OAuth2 login because email is not verified by Google");
            return buildLoginErrorRedirect("email_unverified");
        }

        try {
            User customer = transactionTemplate.execute(status -> provisionOrLinkCustomer(userInfo));
            if (customer == null) {
                return buildLoginErrorRedirect("provider_error");
            }
            String rawTicket = issueOneTimeTicket(customer.getId());
            return buildLoginTicketRedirect(rawTicket, verifiedState.nextPage());
        } catch (ForbiddenOperationException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase(Locale.ROOT) : "";
            String errorCode = msg.contains("deactivated") ? "account_disabled" : "role_not_customer";
            return buildLoginErrorRedirect(errorCode);
        } catch (ConflictException ex) {
            return buildLoginErrorRedirect("account_conflict");
        }
    }

    public AuthResponse exchangeTicket(GoogleOAuthExchangeRequest request) {
        evictExpiredTickets();

        String ticketHash = TokenHashUtil.sha256Hex(request.ticket().trim());
        PendingOAuthTicket consumed = ticketStore.remove(ticketHash);
        if (consumed == null || Instant.now().isAfter(consumed.expiresAt())) {
            throw new UnauthorizedException("Invalid or expired Google sign-in ticket");
        }

        User user = userRepository.findById(consumed.userId())
                .orElseThrow(() -> new UnauthorizedException("Authenticated Google customer account not found"));

        if (user.getRole() != Role.CUSTOMER) {
            throw new ForbiddenOperationException("Google sign-in is available only for Customer accounts");
        }
        if (!user.isEnabled()) {
            throw new ForbiddenOperationException("Your account has been deactivated. Contact support.");
        }

        log.info("Completed Google OAuth2 sign-in for customer userId={}", user.getId());
        return authService.issueTokensForUser(user);
    }

    User provisionOrLinkCustomer(GoogleUserInfo userInfo) {
        String sub = userInfo.sub().trim();
        String normalizedEmail = userInfo.email().trim().toLowerCase(Locale.ROOT);

        Optional<User> byGoogleSub = userRepository.findByGoogleSub(sub);
        if (byGoogleSub.isPresent()) {
            User existing = byGoogleSub.get();
            enforceCustomerAndEnabled(existing);
            return existing;
        }

        Optional<User> byEmail = userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (byEmail.isPresent()) {
            User existing = byEmail.get();
            enforceCustomerAndEnabled(existing);

            if (existing.getGoogleSub() != null
                    && !existing.getGoogleSub().isBlank()
                    && !existing.getGoogleSub().equals(sub)) {
                throw new ConflictException("This customer email is already linked to a different Google account");
            }

            existing.setGoogleSub(sub);
            User linked = userRepository.save(existing);
            log.info("Linked Google identity to existing CUSTOMER userId={}", linked.getId());
            return linked;
        }

        User newCustomer = User.builder()
                .name(resolveDisplayName(userInfo.name(), normalizedEmail))
                .email(normalizedEmail)
                .phone(null)
                .passwordHash(null)
                .role(Role.CUSTOMER)
                .authProvider(AuthProvider.GOOGLE)
                .googleSub(sub)
                .enabled(true)
                .build();

        User saved = userRepository.save(newCustomer);
        log.info("Registered new Google CUSTOMER account id={} email={}", saved.getId(), saved.getEmail());
        return saved;
    }

    private void enforceCustomerAndEnabled(User user) {
        if (user.getRole() != Role.CUSTOMER) {
            log.warn("Blocked Google OAuth2 sign-in attempt on non-customer account userId={} role={}",
                    user.getId(), user.getRole());
            throw new ForbiddenOperationException(
                    "Google sign-in is available only for Customer accounts. Drivers and Admins must sign in with email and password."
            );
        }
        if (!user.isEnabled()) {
            throw new ForbiddenOperationException("Your account has been deactivated. Contact support.");
        }
    }

    private String resolveDisplayName(String rawName, String fallbackEmail) {
        String candidate = rawName != null ? rawName.trim() : "";
        if (candidate.length() < 2) {
            int atIdx = fallbackEmail.indexOf('@');
            candidate = atIdx > 1 ? fallbackEmail.substring(0, atIdx) : "Shipper Account";
        }
        if (candidate.length() > 100) {
            candidate = candidate.substring(0, 100).trim();
        }
        return candidate;
    }

    private String signStateToken(String nonce, String nextPage) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(STATE_SUBJECT)
                .claim("nonce", nonce)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(STATE_TTL_SECONDS)));
        if (nextPage != null) {
            builder.claim("next", nextPage);
        }
        return builder.signWith(stateSigningKey, Jwts.SIG.HS256).compact();
    }

    private VerifiedState verifyStateToken(String signedState, String cookieNonce) {
        if (cookieNonce == null || cookieNonce.isBlank()) {
            throw new UnauthorizedException("Missing OAuth2 state cookie");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(stateSigningKey)
                    .build()
                    .parseSignedClaims(signedState)
                    .getPayload();

            if (!STATE_SUBJECT.equals(claims.getSubject())) {
                throw new UnauthorizedException("Unexpected OAuth2 state subject");
            }

            String tokenNonce = claims.get("nonce", String.class);
            if (tokenNonce == null || !MessageDigest.isEqual(
                    tokenNonce.getBytes(StandardCharsets.UTF_8),
                    cookieNonce.trim().getBytes(StandardCharsets.UTF_8))) {
                throw new UnauthorizedException("OAuth2 state nonce mismatch");
            }

            String nextPage = sanitizeNextPage(claims.get("next", String.class));
            return new VerifiedState(nextPage);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new UnauthorizedException("Expired or tampered OAuth2 state token");
        }
    }

    private String issueOneTimeTicket(Long userId) {
        evictExpiredTickets();
        if (ticketStore.size() >= MAX_CACHED_TICKETS) {
            ticketStore.clear();
        }

        String rawTicket = TokenHashUtil.generateRandomToken();
        String ticketHash = TokenHashUtil.sha256Hex(rawTicket);
        Instant expiresAt = Instant.now().plus(Duration.ofSeconds(properties.effectiveTicketTtlSeconds()));
        ticketStore.put(ticketHash, new PendingOAuthTicket(userId, expiresAt));
        return rawTicket;
    }

    private void evictExpiredTickets() {
        Instant now = Instant.now();
        ticketStore.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
    }

    private String sanitizeNextPage(String requestedNext) {
        if (requestedNext == null || requestedNext.isBlank()) {
            return null;
        }
        String trimmed = requestedNext.trim();
        return SAFE_NEXT_PAGE.matcher(trimmed).matches() ? trimmed : null;
    }

    private String buildLoginTicketRedirect(String rawTicket, String nextPage) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(properties.normalizedFrontendBaseUrl() + "/login.html")
                .queryParam("oauth_ticket", rawTicket);
        if (nextPage != null) {
            builder.queryParam("next", nextPage);
        }
        return builder.build().encode().toUriString();
    }

    private String buildLoginErrorRedirect(String errorCode) {
        return UriComponentsBuilder
                .fromUriString(properties.normalizedFrontendBaseUrl() + "/login.html")
                .queryParam("oauth_error", errorCode)
                .build()
                .encode()
                .toUriString();
    }

    public record AuthorizationRedirect(
            String redirectUrl,
            String cookieNonce,
            boolean configured
    ) {
    }

    private record VerifiedState(String nextPage) {
    }

    private record PendingOAuthTicket(Long userId, Instant expiresAt) {
    }
}
