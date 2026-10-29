package com.sam.apnasamaan.auth.security;

import com.sam.apnasamaan.auth.config.RateLimitProperties;
import com.sam.apnasamaan.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRateLimiterTest {

    private AuthRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new AuthRateLimiter(new RateLimitProperties(3, 60, 2, 300));
    }

    @Test
    void loginLimit_blocksFourthAttemptFromSameIp_andAllowsDifferentIp() {
        assertThatCode(() -> {
            rateLimiter.checkLoginAllowed("203.0.113.10");
            rateLimiter.checkLoginAllowed("203.0.113.10");
            rateLimiter.checkLoginAllowed("203.0.113.10");
        }).doesNotThrowAnyException();

        assertThatThrownBy(() -> rateLimiter.checkLoginAllowed("203.0.113.10"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("Too many login attempts");

        assertThatCode(() -> rateLimiter.checkLoginAllowed("203.0.113.99"))
                .doesNotThrowAnyException();
    }

    @Test
    void forgotPasswordLimit_blocksThirdAttemptFromSameIp() {
        rateLimiter.checkForgotPasswordAllowed("198.51.100.5");
        rateLimiter.checkForgotPasswordAllowed("198.51.100.5");

        assertThatThrownBy(() -> rateLimiter.checkForgotPasswordAllowed("198.51.100.5"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("Too many password reset requests");
    }
}
