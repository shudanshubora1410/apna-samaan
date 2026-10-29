package com.sam.apnasamaan.auth.controller;

import com.sam.apnasamaan.auth.dto.AuthResponse;
import com.sam.apnasamaan.auth.dto.CustomerRegisterRequest;
import com.sam.apnasamaan.auth.dto.ForgotPasswordRequest;
import com.sam.apnasamaan.auth.dto.LoginRequest;
import com.sam.apnasamaan.auth.dto.LogoutRequest;
import com.sam.apnasamaan.auth.dto.MessageResponse;
import com.sam.apnasamaan.auth.dto.RefreshTokenRequest;
import com.sam.apnasamaan.auth.dto.ResetPasswordRequest;
import com.sam.apnasamaan.auth.dto.UserSummaryResponse;
import com.sam.apnasamaan.auth.security.AuthRateLimiter;
import com.sam.apnasamaan.auth.security.UserPrincipal;
import com.sam.apnasamaan.auth.service.AuthService;
import com.sam.apnasamaan.auth.service.PasswordResetService;
import com.sam.apnasamaan.common.config.OpenApiConfig;
import com.sam.apnasamaan.common.dto.ApiErrorResponse;
import com.sam.apnasamaan.driver.dto.DriverRegisterForm;
import com.sam.apnasamaan.driver.dto.DriverRegistrationResponse;
import com.sam.apnasamaan.driver.service.DriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Registration, login, JWT refresh/logout, and password reset")
public class AuthController {

    private static final String FORGOT_PASSWORD_GENERIC_MESSAGE =
            "If an account exists for that email, a password reset link has been sent.";

    private final AuthService authService;
    private final DriverService driverService;
    private final PasswordResetService passwordResetService;
    private final AuthRateLimiter rateLimiter;

    public AuthController(
            AuthService authService,
            DriverService driverService,
            PasswordResetService passwordResetService,
            AuthRateLimiter rateLimiter
    ) {
        this.authService = authService;
        this.driverService = driverService;
        this.passwordResetService = passwordResetService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register/customer")
    @Operation(
            summary = "Register a new customer account",
            description = "Creates an enabled CUSTOMER account with a BCrypt-hashed password and returns a 15-minute JWT access token plus a 7-day refresh token."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Customer registered"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Email already registered",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<AuthResponse> registerCustomer(@Valid @RequestBody CustomerRegisterRequest request) {
        AuthResponse response = authService.registerCustomer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping(value = "/register/driver", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Register a new driver with vehicle details and verification documents",
            description = "Multipart upload requiring drivingLicence and vehicleRc files (real JPEG/PNG/PDF, max 5 MB each). Driver starts in PENDING status until approved by an admin."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Driver registered in PENDING status"),
            @ApiResponse(responseCode = "400", description = "Validation or invalid file format/size",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Email or vehicle number already registered",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<DriverRegistrationResponse> registerDriver(
            @ParameterObject @Valid @ModelAttribute DriverRegisterForm form,
            @RequestPart("drivingLicence") MultipartFile drivingLicence,
            @RequestPart("vehicleRc") MultipartFile vehicleRc
    ) {
        DriverRegistrationResponse response = driverService.registerDriver(form, drivingLicence, vehicleRc);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    @Operation(
            summary = "Authenticate user and obtain JWT + refresh token",
            description = "Rate-limited per IP. Authenticates Customers, Drivers, and Admins and returns a short-lived JWT access token plus a 7-day refresh token."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Account deactivated",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many login attempts",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        rateLimiter.checkLoginAllowed(resolveClientIp(servletRequest));
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Rotate refresh token and issue a new JWT access token",
            description = "Consumes a valid unrevoked refresh token, marks it replaced, and returns a brand-new access + refresh token pair."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tokens rotated"),
            @ApiResponse(responseCode = "401", description = "Invalid, expired, or reused refresh token",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    @Operation(
            summary = "Logout and revoke refresh token",
            description = "Idempotently revokes the supplied refresh token so it can no longer mint access tokens."
    )
    public ResponseEntity<MessageResponse> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
        return ResponseEntity.ok(new MessageResponse("Logged out successfully"));
    }

    @PostMapping("/forgot-password")
    @Operation(
            summary = "Request a 15-minute password reset email",
            description = "Rate-limited per IP. Always returns HTTP 200 with a generic message so account existence is never leaked."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Generic confirmation returned"),
            @ApiResponse(responseCode = "429", description = "Too many password reset requests",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<MessageResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest servletRequest
    ) {
        rateLimiter.checkForgotPasswordAllowed(resolveClientIp(servletRequest));
        passwordResetService.requestPasswordReset(request);
        return ResponseEntity.ok(new MessageResponse(FORGOT_PASSWORD_GENERIC_MESSAGE));
    }

    @PostMapping("/reset-password")
    @Operation(
            summary = "Reset password using a single-use token",
            description = "Verifies the hashed 15-minute token, updates the BCrypt password, marks the token used, and revokes all active refresh tokens."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password updated"),
            @ApiResponse(responseCode = "400", description = "Invalid, expired, or already-used token",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<MessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request);
        return ResponseEntity.ok(new MessageResponse("Password has been reset successfully. Please log in."));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME)
    @Operation(
            summary = "Get current authenticated user summary",
            description = "Returns the authenticated user's profile summary for any role (CUSTOMER, DRIVER, ADMIN)."
    )
    public ResponseEntity<UserSummaryResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(authService.getCurrentUser(principal.getId()));
    }

    private String resolveClientIp(HttpServletRequest request) {
        // Use socket remoteAddr rather than trusting unverified X-Forwarded-For headers from clients
        return request.getRemoteAddr();
    }
}
