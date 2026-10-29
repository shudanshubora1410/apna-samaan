package com.sam.apnasamaan.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.apnasamaan.auth.security.CustomUserDetailsService;
import com.sam.apnasamaan.auth.security.JwtAuthenticationFilter;
import com.sam.apnasamaan.auth.security.JwtService;
import com.sam.apnasamaan.common.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AppProperties.class)
public class SecurityConfig {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<JwtService> jwtServiceProvider;
    private final ObjectProvider<CustomUserDetailsService> userDetailsServiceProvider;

    public SecurityConfig(
            AppProperties appProperties,
            ObjectMapper objectMapper,
            ObjectProvider<JwtService> jwtServiceProvider,
            ObjectProvider<CustomUserDetailsService> userDetailsServiceProvider
    ) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.jwtServiceProvider = jwtServiceProvider;
        this.userDetailsServiceProvider = userDetailsServiceProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Strength 10 is OWASP-recommended balance between brute-force resistance and login latency
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless REST API authenticated via JWT Bearer headers (no server-side HTTP session)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                // Security filters run before DispatcherServlet, so we write ApiErrorResponse directly here
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeSecurityError(response, HttpStatus.UNAUTHORIZED,
                                        "Authentication is required to access this resource", request.getRequestURI()))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeSecurityError(response, HttpStatus.FORBIDDEN,
                                        "You do not have permission to perform this action", request.getRequestURI())))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                HttpMethod.GET,
                                "/",
                                "/*.html",
                                "/css/**",
                                "/js/**",
                                "/assets/**",
                                "/favicon.svg",
                                "/favicon.ico",
                                "/api/v1/health",
                                "/api/v1/vehicle-types",
                                "/api/v1/maps/geocode",
                                "/api/v1/auth/google/status",
                                "/api/v1/auth/google/authorize",
                                "/api/v1/auth/google/callback"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/register/customer",
                                "/api/v1/auth/register/driver",
                                "/api/v1/auth/login",
                                "/api/v1/auth/google/exchange",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/reset-password",
                                "/api/v1/shipments/quote",
                                "/api/v1/payments/webhook"
                        ).permitAll()
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .anyRequest().authenticated());

        JwtService jwtService = jwtServiceProvider.getIfAvailable();
        CustomUserDetailsService userDetailsService = userDetailsServiceProvider.getIfAvailable();
        if (jwtService != null && userDetailsService != null) {
            http.addFilterBefore(
                    new JwtAuthenticationFilter(jwtService, userDetailsService),
                    UsernamePasswordAuthenticationFilter.class
            );
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = appProperties.cors().allowedOrigins().stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        // Restrict cross-origin calls strictly to configured frontend origins (never "*")
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private void writeSecurityError(
            HttpServletResponse response,
            HttpStatus status,
            String message,
            String path
    ) throws IOException {
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path,
                Map.of()
        );
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
