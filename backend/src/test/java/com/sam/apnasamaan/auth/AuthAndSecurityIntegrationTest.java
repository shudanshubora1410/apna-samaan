package com.sam.apnasamaan.auth;

import com.sam.apnasamaan.BaseIntegrationTest;
import com.sam.apnasamaan.auth.dto.AuthResponse;
import com.sam.apnasamaan.auth.dto.CustomerRegisterRequest;
import com.sam.apnasamaan.auth.dto.ForgotPasswordRequest;
import com.sam.apnasamaan.auth.dto.LoginRequest;
import com.sam.apnasamaan.auth.dto.LogoutRequest;
import com.sam.apnasamaan.auth.dto.MessageResponse;
import com.sam.apnasamaan.auth.dto.RefreshTokenRequest;
import com.sam.apnasamaan.auth.dto.ResetPasswordRequest;
import com.sam.apnasamaan.auth.entity.Role;
import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.auth.repository.PasswordResetTokenRepository;
import com.sam.apnasamaan.auth.repository.RefreshTokenRepository;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.auth.security.AuthRateLimiter;
import com.sam.apnasamaan.common.dto.ApiErrorResponse;
import com.sam.apnasamaan.customer.dto.CustomerProfileResponse;
import com.sam.apnasamaan.customer.dto.CustomerProfileUpdateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

class AuthAndSecurityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordResetTokenRepository resetTokenRepository;

    @Autowired
    private AuthRateLimiter rateLimiter;

    private final List<String> createdTestEmails = new ArrayList<>();

    @BeforeEach
    void setUp() {
        rateLimiter.clearAll();
        reset(javaMailSender);
    }

    @AfterEach
    void cleanUpTestUsers() {
        // Delete only test accounts created during this run so local developer DB rows stay untouched
        for (String email : createdTestEmails) {
            userRepository.findByEmailIgnoreCase(email).ifPresent(userRepository::delete);
        }
        createdTestEmails.clear();
        rateLimiter.clearAll();
    }

    @Test
    void adminBootstrap_createsAdminAndAllowsLogin() {
        ResponseEntity<AuthResponse> loginRes = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(TEST_ADMIN_EMAIL, TEST_ADMIN_PASSWORD),
                AuthResponse.class
        );

        assertThat(loginRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginRes.getBody()).isNotNull();
        assertThat(loginRes.getBody().user().role()).isEqualTo(Role.ADMIN);
        assertThat(loginRes.getBody().accessToken()).isNotBlank();
    }

    @Test
    void customerRegistration_validatesInput_hashesPassword_andBlocksDuplicateEmail() {
        String email = uniqueEmail("aarav");
        String password = "Cust#" + UUID.randomUUID().toString().substring(0, 12);

        // 1. Invalid Indian mobile number -> 400 with fieldErrors.phone
        ResponseEntity<ApiErrorResponse> invalidPhone = restTemplate.postForEntity(
                "/api/v1/auth/register/customer",
                new CustomerRegisterRequest("Aarav Sharma", email, "12345", password),
                ApiErrorResponse.class
        );
        assertThat(invalidPhone.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalidPhone.getBody()).isNotNull();
        assertThat(invalidPhone.getBody().fieldErrors()).containsKey("phone");

        // 2. Valid registration -> 201 Created, never leaks passwordHash
        ResponseEntity<String> rawRegisterResponse = restTemplate.postForEntity(
                "/api/v1/auth/register/customer",
                new CustomerRegisterRequest("Aarav Sharma", email, "9876543210", password),
                String.class
        );
        assertThat(rawRegisterResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rawRegisterResponse.getBody()).doesNotContain("passwordHash", password);

        User stored = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("$2a$");
        assertThat(stored.getRole()).isEqualTo(Role.CUSTOMER);

        // 3. Duplicate registration (even with uppercase email) -> 409 Conflict
        ResponseEntity<ApiErrorResponse> duplicate = restTemplate.postForEntity(
                "/api/v1/auth/register/customer",
                new CustomerRegisterRequest("Aarav Sharma", email.toUpperCase(), "9876543210", password),
                ApiErrorResponse.class
        );
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void login_refreshRotation_reuseDetection_andLogoutWorkEndToEnd() {
        String email = uniqueEmail("priya");
        String password = "Cust#" + UUID.randomUUID().toString().substring(0, 12);

        AuthResponse registered = registerCustomer("Priya Nair", email, "9812345678", password);
        String refresh1 = registered.refreshToken();

        // Wrong password -> 401
        ResponseEntity<ApiErrorResponse> badLogin = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, "wrong-password-123"),
                ApiErrorResponse.class
        );
        assertThat(badLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Rotate refresh1 -> receives refresh2
        ResponseEntity<AuthResponse> rotated = restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                new RefreshTokenRequest(refresh1),
                AuthResponse.class
        );
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rotated.getBody()).isNotNull();
        String refresh2 = rotated.getBody().refreshToken();
        assertThat(refresh2).isNotEqualTo(refresh1);

        // Replaying already-used refresh1 triggers reuse detection -> 401 AND revokes refresh2!
        ResponseEntity<ApiErrorResponse> replay = restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                new RefreshTokenRequest(refresh1),
                ApiErrorResponse.class
        );
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<ApiErrorResponse> refresh2AfterReplay = restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                new RefreshTokenRequest(refresh2),
                ApiErrorResponse.class
        );
        assertThat(refresh2AfterReplay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Fresh login + explicit logout revokes the new refresh token
        AuthResponse freshLogin = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, password),
                AuthResponse.class
        ).getBody();
        assertThat(freshLogin).isNotNull();

        ResponseEntity<MessageResponse> logoutRes = restTemplate.postForEntity(
                "/api/v1/auth/logout",
                new LogoutRequest(freshLogin.refreshToken()),
                MessageResponse.class
        );
        assertThat(logoutRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<ApiErrorResponse> refreshAfterLogout = restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                new RefreshTokenRequest(freshLogin.refreshToken()),
                ApiErrorResponse.class
        );
        assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void roleAndOwnershipRules_blockCrossCustomerAccess_andEnforceDeactivation() {
        String pass = "Cust#" + UUID.randomUUID().toString().substring(0, 12);
        AuthResponse customerA = registerCustomer("Rohan Verma", uniqueEmail("rohan"), "9876511111", pass);
        AuthResponse customerB = registerCustomer("Neha Gupta", uniqueEmail("neha"), "9876522222", pass);
        AuthResponse admin = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(TEST_ADMIN_EMAIL, TEST_ADMIN_PASSWORD),
                AuthResponse.class
        ).getBody();
        assertThat(admin).isNotNull();

        // Customer A updates own profile -> 200 OK
        ResponseEntity<CustomerProfileResponse> updatedA = restTemplate.exchange(
                "/api/v1/customer/me",
                HttpMethod.PUT,
                bearerEntity(customerA.accessToken(), new CustomerProfileUpdateRequest("Rohan K. Verma", "9876599999")),
                CustomerProfileResponse.class
        );
        assertThat(updatedA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updatedA.getBody()).isNotNull();
        assertThat(updatedA.getBody().name()).isEqualTo("Rohan K. Verma");

        // Customer B tries reading Customer A's profile by ID -> 403 Forbidden (ownership check in service)
        ResponseEntity<ApiErrorResponse> crossRead = restTemplate.exchange(
                "/api/v1/customer/" + customerA.user().id(),
                HttpMethod.GET,
                bearerEntity(customerB.accessToken(), null),
                ApiErrorResponse.class
        );
        assertThat(crossRead.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Admin CAN read Customer A by ID -> 200 OK, but CANNOT call CUSTOMER-only /api/v1/customer/me -> 403
        ResponseEntity<CustomerProfileResponse> adminRead = restTemplate.exchange(
                "/api/v1/customer/" + customerA.user().id(),
                HttpMethod.GET,
                bearerEntity(admin.accessToken(), null),
                CustomerProfileResponse.class
        );
        assertThat(adminRead.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<ApiErrorResponse> adminCustomerMe = restTemplate.exchange(
                "/api/v1/customer/me",
                HttpMethod.GET,
                bearerEntity(admin.accessToken(), null),
                ApiErrorResponse.class
        );
        assertThat(adminCustomerMe.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Deactivating Customer B immediately invalidates existing JWT on /api/v1/auth/me -> 401
        User userB = userRepository.findById(customerB.user().id()).orElseThrow();
        userB.setEnabled(false);
        userRepository.save(userB);

        ResponseEntity<ApiErrorResponse> disabledJwtCall = restTemplate.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                bearerEntity(customerB.accessToken(), null),
                ApiErrorResponse.class
        );
        assertThat(disabledJwtCall.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void passwordResetFlow_preventsEnumeration_resetsPasswordOnce_andEnforcesRateLimit() {
        String email = uniqueEmail("vikram");
        String oldPass = "OldPass#" + UUID.randomUUID().toString().substring(0, 10);
        String newPass = "NewPass#" + UUID.randomUUID().toString().substring(0, 10);
        registerCustomer("Vikram Singh", email, "9898989898", oldPass);

        // Unknown email returns exact same 200 message (never reveals account existence)
        ResponseEntity<MessageResponse> unknownEmailRes = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password",
                new ForgotPasswordRequest("nobody." + UUID.randomUUID() + "@example.com"),
                MessageResponse.class
        );
        ResponseEntity<MessageResponse> knownEmailRes = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password",
                new ForgotPasswordRequest(email),
                MessageResponse.class
        );
        assertThat(unknownEmailRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(knownEmailRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unknownEmailRes.getBody().message()).isEqualTo(knownEmailRes.getBody().message());

        // Capture emailed reset link and extract the single-use raw token
        ArgumentCaptor<SimpleMailMessage> mailCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(mailCaptor.capture());
        String emailBody = mailCaptor.getValue().getText();
        Matcher matcher = Pattern.compile("token=([^\\s]+)").matcher(emailBody);
        assertThat(matcher.find()).isTrue();
        String rawResetToken = URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);

        // Consume reset token -> 200 OK
        ResponseEntity<MessageResponse> resetOk = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest(rawResetToken, newPass),
                MessageResponse.class
        );
        assertThat(resetOk.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Reusing the same reset token a second time -> 400 Bad Request
        ResponseEntity<ApiErrorResponse> reuseToken = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new ResetPasswordRequest(rawResetToken, newPass),
                ApiErrorResponse.class
        );
        assertThat(reuseToken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Old password fails; new password succeeds
        assertThat(restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, oldPass),
                ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<AuthResponse> newLogin = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, newPass),
                AuthResponse.class
        );
        assertThat(newLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(newLogin.getBody().user().email()).isEqualTo(email);

        // Rate limiting: forgot-password allows 5 requests per window; 6th returns 429 Too Many Requests
        restTemplate.postForEntity("/api/v1/auth/forgot-password", new ForgotPasswordRequest(email), String.class);
        restTemplate.postForEntity("/api/v1/auth/forgot-password", new ForgotPasswordRequest(email), String.class);
        restTemplate.postForEntity("/api/v1/auth/forgot-password", new ForgotPasswordRequest(email), String.class);
        ResponseEntity<ApiErrorResponse> rateLimited = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password",
                new ForgotPasswordRequest(email),
                ApiErrorResponse.class
        );
        assertThat(rateLimited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private AuthResponse registerCustomer(String name, String email, String phone, String password) {
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity(
                "/api/v1/auth/register/customer",
                new CustomerRegisterRequest(name, email, phone, password),
                AuthResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    private String uniqueEmail(String prefix) {
        String email = prefix + "." + UUID.randomUUID().toString().substring(0, 8) + "@apnasamaan.test";
        createdTestEmails.add(email);
        return email;
    }

    private <T> HttpEntity<T> bearerEntity(String accessToken, T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(body, headers);
    }
}
