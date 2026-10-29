package com.sam.apnasamaan.auth.service;

import com.sam.apnasamaan.auth.config.JwtProperties;
import com.sam.apnasamaan.auth.dto.AuthResponse;
import com.sam.apnasamaan.auth.dto.CustomerRegisterRequest;
import com.sam.apnasamaan.auth.dto.LoginRequest;
import com.sam.apnasamaan.auth.dto.LogoutRequest;
import com.sam.apnasamaan.auth.dto.RefreshTokenRequest;
import com.sam.apnasamaan.auth.dto.UserSummaryResponse;
import com.sam.apnasamaan.auth.entity.RefreshToken;
import com.sam.apnasamaan.auth.entity.Role;
import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.auth.mapper.UserMapper;
import com.sam.apnasamaan.auth.repository.RefreshTokenRepository;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.auth.security.JwtService;
import com.sam.apnasamaan.common.exception.ConflictException;
import com.sam.apnasamaan.common.exception.ForbiddenOperationException;
import com.sam.apnasamaan.common.exception.ResourceNotFoundException;
import com.sam.apnasamaan.common.exception.UnauthorizedException;
import com.sam.apnasamaan.common.util.TokenHashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Slf4j
@Service
public class AuthService {

    private static final String TOKEN_TYPE_BEARER = "Bearer";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final UserMapper userMapper;
    private final String dummyPasswordHash;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            JwtProperties jwtProperties,
            UserMapper userMapper
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.userMapper = userMapper;
        // Equalizes BCrypt work when an email is unknown or belongs to a Google-only customer
        this.dummyPasswordHash = passwordEncoder.encode("apna-samaan-timing-guard");
    }

    @Transactional
    public AuthResponse registerCustomer(CustomerRegisterRequest request) {
        String normalizedEmail = normalizeEmail(request.email());

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("An account with this email already exists");
        }

        User customer = User.builder()
                .name(request.name().trim())
                .email(normalizedEmail)
                .phone(request.phone().trim())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(Role.CUSTOMER)
                .enabled(true)
                .build();

        User saved = userRepository.save(customer);
        log.info("Registered new CUSTOMER account id={} email={}", saved.getId(), saved.getEmail());

        return issueTokensForUser(saved);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = normalizeEmail(request.email());

        // Same generic error for wrong email, Google-only account, or wrong password to prevent enumeration
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        if (user == null || user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw new UnauthorizedException("Invalid email or password");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        if (!user.isEnabled()) {
            throw new ForbiddenOperationException("Your account has been deactivated. Contact support.");
        }

        log.info("Successful login for userId={} role={}", user.getId(), user.getRole());
        return issueTokensForUser(user);
    }

    /**
     * Rotates a valid 7-day refresh token into a new access + refresh token pair.
     * `noRollbackFor` ensures token revocations are committed even when we reject the request with 401.
     */
    @Transactional(noRollbackFor = {UnauthorizedException.class, ForbiddenOperationException.class})
    public AuthResponse refresh(RefreshTokenRequest request) {
        String incomingHash = TokenHashUtil.sha256Hex(request.refreshToken().trim());

        RefreshToken storedToken = refreshTokenRepository.findByTokenHashWithUser(incomingHash)
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired refresh token"));

        User user = storedToken.getUser();

        if (storedToken.isRevoked()) {
            // Replaying an already-rotated token suggests theft; revoke all active sessions for safety
            refreshTokenRepository.revokeAllActiveByUserId(user.getId());
            log.warn("Detected reuse of revoked refresh token for userId={}; revoked all active sessions", user.getId());
            throw new UnauthorizedException("Refresh token has already been used or revoked");
        }

        if (storedToken.getExpiresAt().isBefore(Instant.now())) {
            storedToken.setRevoked(true);
            refreshTokenRepository.save(storedToken);
            throw new UnauthorizedException("Refresh token has expired. Please log in again.");
        }

        if (!user.isEnabled()) {
            refreshTokenRepository.revokeAllActiveByUserId(user.getId());
            throw new ForbiddenOperationException("Your account has been deactivated. Contact support.");
        }

        String newRawRefresh = TokenHashUtil.generateRandomToken();
        String newRefreshHash = TokenHashUtil.sha256Hex(newRawRefresh);

        storedToken.setRevoked(true);
        storedToken.setReplacedByHash(newRefreshHash);
        refreshTokenRepository.save(storedToken);

        saveRefreshTokenHash(user, newRefreshHash);
        String accessToken = jwtService.generateAccessToken(user);

        return new AuthResponse(
                accessToken,
                newRawRefresh,
                TOKEN_TYPE_BEARER,
                jwtService.getAccessExpirationSeconds(),
                userMapper.toSummary(user)
        );
    }

    @Transactional
    public void logout(LogoutRequest request) {
        String incomingHash = TokenHashUtil.sha256Hex(request.refreshToken().trim());
        refreshTokenRepository.findByTokenHashWithUser(incomingHash).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.setRevoked(true);
                refreshTokenRepository.save(token);
                log.info("Revoked refresh token on logout for userId={}", token.getUser().getId());
            }
        });
    }

    @Transactional(readOnly = true)
    public UserSummaryResponse getCurrentUser(Long authenticatedUserId) {
        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        return userMapper.toSummary(user);
    }

    @Transactional
    public AuthResponse issueTokensForUser(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = TokenHashUtil.generateRandomToken();
        String refreshTokenHash = TokenHashUtil.sha256Hex(rawRefreshToken);

        saveRefreshTokenHash(user, refreshTokenHash);

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                TOKEN_TYPE_BEARER,
                jwtService.getAccessExpirationSeconds(),
                userMapper.toSummary(user)
        );
    }

    private void saveRefreshTokenHash(User user, String tokenHash) {
        Instant expiresAt = Instant.now().plus(Duration.ofDays(jwtProperties.refreshExpirationDays()));
        RefreshToken entity = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(expiresAt)
                .revoked(false)
                .build();
        refreshTokenRepository.save(entity);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
