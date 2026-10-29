package com.sam.apnasamaan.auth.service;

import com.sam.apnasamaan.auth.config.PasswordResetProperties;
import com.sam.apnasamaan.auth.dto.ForgotPasswordRequest;
import com.sam.apnasamaan.auth.dto.ResetPasswordRequest;
import com.sam.apnasamaan.auth.entity.PasswordResetToken;
import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.auth.repository.PasswordResetTokenRepository;
import com.sam.apnasamaan.auth.repository.RefreshTokenRepository;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.common.mail.EmailService;
import com.sam.apnasamaan.common.util.TokenHashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final PasswordResetProperties resetProperties;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository resetTokenRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            EmailService emailService,
            PasswordResetProperties resetProperties
    ) {
        this.userRepository = userRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.resetProperties = resetProperties;
    }

    @Transactional
    public void requestPasswordReset(ForgotPasswordRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(normalizedEmail);

        // Return silently if email is unknown so callers can never tell whether an account exists
        if (userOpt.isEmpty() || !userOpt.get().isEnabled()) {
            log.info("Password reset requested for non-existent or disabled email; returning generic response");
            return;
        }

        User user = userOpt.get();
        Instant now = Instant.now();

        // Invalidate any earlier unused reset links before issuing a fresh 15-minute token
        resetTokenRepository.invalidateUnusedTokensForUser(user.getId(), now);

        String rawToken = TokenHashUtil.generateRandomToken();
        String tokenHash = TokenHashUtil.sha256Hex(rawToken);
        Instant expiresAt = now.plus(Duration.ofMinutes(resetProperties.expirationMinutes()));

        PasswordResetToken entity = PasswordResetToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(expiresAt)
                .build();
        resetTokenRepository.save(entity);

        String resetLink = buildResetLink(rawToken);
        emailService.sendPasswordResetEmail(
                user.getEmail(),
                user.getName(),
                resetLink,
                resetProperties.expirationMinutes()
        );
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String tokenHash = TokenHashUtil.sha256Hex(request.token().trim());
        Instant now = Instant.now();

        PasswordResetToken resetToken = resetTokenRepository.findByTokenHashWithUser(tokenHash)
                .orElseThrow(() -> new BadRequestException("Invalid or expired password reset token"));

        if (resetToken.isUsed() || resetToken.isExpired(now)) {
            throw new BadRequestException("Invalid or expired password reset token");
        }

        User user = resetToken.getUser();
        if (!user.isEnabled()) {
            throw new BadRequestException("Invalid or expired password reset token");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        resetToken.setUsedAt(now);
        resetTokenRepository.save(resetToken);

        // Force re-login on all devices after a password reset
        refreshTokenRepository.revokeAllActiveByUserId(user.getId());
        log.info("Completed password reset and revoked active sessions for userId={}", user.getId());
    }

    private String buildResetLink(String rawToken) {
        String baseUrl = resetProperties.frontendBaseUrl().replaceAll("/+$", "");
        String encodedToken = URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        return baseUrl + "/reset-password.html?token=" + encodedToken;
    }
}
