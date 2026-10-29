package com.sam.apnasamaan.auth.security;

import com.sam.apnasamaan.auth.config.RateLimitProperties;
import com.sam.apnasamaan.common.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe in-memory fixed-window rate limiter protecting login and forgot-password
 * endpoints against brute-force password guessing and email-flood abuse.
 */
@Component
public class AuthRateLimiter {

    private static final int MAX_TRACKED_BUCKETS = 10_000;

    private final RateLimitProperties properties;
    private final ConcurrentHashMap<String, WindowBucket> buckets = new ConcurrentHashMap<>();

    public AuthRateLimiter(RateLimitProperties properties) {
        this.properties = properties;
    }

    public void checkLoginAllowed(String clientKey) {
        enforceLimit(
                "login:" + normalizeKey(clientKey),
                properties.loginMaxRequests(),
                Duration.ofSeconds(properties.loginWindowSeconds()),
                "Too many login attempts. Please wait a minute and try again."
        );
    }

    public void checkForgotPasswordAllowed(String clientKey) {
        enforceLimit(
                "forgot:" + normalizeKey(clientKey),
                properties.forgotPasswordMaxRequests(),
                Duration.ofSeconds(properties.forgotPasswordWindowSeconds()),
                "Too many password reset requests. Please wait a few minutes and try again."
        );
    }

    public void clearAll() {
        buckets.clear();
    }

    private void enforceLimit(String key, int maxRequests, Duration window, String errorMessage) {
        Instant now = Instant.now();
        if (buckets.size() > MAX_TRACKED_BUCKETS) {
            buckets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        }

        AtomicBoolean allowed = new AtomicBoolean(true);
        buckets.compute(key, (k, existing) -> {
            if (existing == null || existing.expiresAt().isBefore(now)) {
                return new WindowBucket(1, now.plus(window));
            }
            if (existing.count() >= maxRequests) {
                allowed.set(false);
                return existing;
            }
            return new WindowBucket(existing.count() + 1, existing.expiresAt());
        });

        if (!allowed.get()) {
            throw new TooManyRequestsException(errorMessage);
        }
    }

    private String normalizeKey(String clientKey) {
        return (clientKey == null || clientKey.isBlank()) ? "unknown" : clientKey.trim();
    }

    private record WindowBucket(int count, Instant expiresAt) {
    }
}
