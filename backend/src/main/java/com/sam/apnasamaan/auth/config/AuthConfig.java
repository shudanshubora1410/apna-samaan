package com.sam.apnasamaan.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        JwtProperties.class,
        AdminProperties.class,
        PasswordResetProperties.class,
        RateLimitProperties.class,
        GoogleOAuthProperties.class
})
public class AuthConfig {
}
