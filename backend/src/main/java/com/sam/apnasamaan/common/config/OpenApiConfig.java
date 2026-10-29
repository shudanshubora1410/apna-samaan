package com.sam.apnasamaan.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI apnaSamaanOpenAPI(AppProperties appProperties) {
        return new OpenAPI()
                .info(new Info()
                        .title("Apna Samaan API")
                        .version(appProperties.version())
                        .description("REST API for Apna Samaan — intra-city and inter-city goods delivery platform.")
                        .contact(new Contact()
                                .name("Apna Samaan Support")
                                .email(appProperties.supportEmail())
                                .url("https://github.com/shudanshubora1410")))
                // Pre-register the JWT Bearer scheme so Swagger UI gets an "Authorize" button for Phase 1+
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH_SCHEME, new SecurityScheme()
                                .name(BEARER_AUTH_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
