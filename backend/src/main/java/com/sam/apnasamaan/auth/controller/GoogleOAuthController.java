package com.sam.apnasamaan.auth.controller;

import com.sam.apnasamaan.auth.dto.AuthResponse;
import com.sam.apnasamaan.auth.dto.GoogleOAuthExchangeRequest;
import com.sam.apnasamaan.auth.dto.GoogleOAuthStatusResponse;
import com.sam.apnasamaan.auth.security.AuthRateLimiter;
import com.sam.apnasamaan.auth.service.GoogleOAuthService;
import com.sam.apnasamaan.common.dto.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/auth/google")
@Tag(name = "Auth", description = "Registration, login, JWT refresh/logout, password reset, and Customer Google OAuth2")
public class GoogleOAuthController {

    private static final String STATE_COOKIE_PATH = "/api/v1/auth/google";

    private final GoogleOAuthService googleOAuthService;
    private final AuthRateLimiter rateLimiter;

    public GoogleOAuthController(GoogleOAuthService googleOAuthService, AuthRateLimiter rateLimiter) {
        this.googleOAuthService = googleOAuthService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/status")
    @Operation(
            summary = "Check whether Customer Google OAuth2 login is configured",
            description = "Returns enabled=true only when non-placeholder GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are present."
    )
    public ResponseEntity<GoogleOAuthStatusResponse> status() {
        return ResponseEntity.ok(googleOAuthService.getStatus());
    }

    @GetMapping("/authorize")
    @Operation(
            summary = "Initiate Google OAuth2 Authorization Code redirect for a Customer",
            description = "Issues a stateless signed CSRF state token + HttpOnly nonce cookie and redirects (302) to Google's consent screen."
    )
    public ResponseEntity<Void> authorize(
            @RequestParam(name = "next", required = false) String next,
            HttpServletRequest servletRequest
    ) {
        rateLimiter.checkLoginAllowed(servletRequest.getRemoteAddr());
        GoogleOAuthService.AuthorizationRedirect redirect = googleOAuthService.startAuthorization(next);

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(redirect.redirectUrl()));
        if (redirect.configured() && redirect.cookieNonce() != null) {
            ResponseCookie stateCookie = ResponseCookie.from(GoogleOAuthService.STATE_COOKIE_NAME, redirect.cookieNonce())
                    .httpOnly(true)
                    .secure(servletRequest.isSecure())
                    .sameSite("Lax")
                    .path(STATE_COOKIE_PATH)
                    .maxAge(GoogleOAuthService.STATE_TTL_SECONDS)
                    .build();
            headers.add(HttpHeaders.SET_COOKIE, stateCookie.toString());
        }

        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    @GetMapping("/callback")
    @Operation(
            summary = "Handle Google OAuth2 callback redirect",
            description = "Verifies CSRF state, exchanges code for verified Google profile, provisions/links a CUSTOMER account, and redirects to login.html with a 60-second one-time ticket."
    )
    public ResponseEntity<Void> callback(
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String error,
            @CookieValue(name = GoogleOAuthService.STATE_COOKIE_NAME, required = false) String cookieNonce,
            HttpServletRequest servletRequest
    ) {
        String frontendRedirect = googleOAuthService.handleCallback(code, state, error, cookieNonce);

        ResponseCookie clearCookie = ResponseCookie.from(GoogleOAuthService.STATE_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(servletRequest.isSecure())
                .sameSite("Lax")
                .path(STATE_COOKIE_PATH)
                .maxAge(0)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(frontendRedirect));
        headers.add(HttpHeaders.SET_COOKIE, clearCookie.toString());
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    @PostMapping("/exchange")
    @Operation(
            summary = "Exchange a one-time Google OAuth2 callback ticket for JWT access + refresh tokens",
            description = "Atomically consumes the 60-second single-use ticket and issues standard Apna Samaan JWT access and refresh tokens."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tokens issued for authenticated Customer"),
            @ApiResponse(responseCode = "401", description = "Invalid, expired, or already-used ticket",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Non-customer or deactivated account",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<AuthResponse> exchange(
            @Valid @RequestBody GoogleOAuthExchangeRequest request,
            HttpServletRequest servletRequest
    ) {
        rateLimiter.checkLoginAllowed(servletRequest.getRemoteAddr());
        return ResponseEntity.ok(googleOAuthService.exchangeTicket(request));
    }
}
