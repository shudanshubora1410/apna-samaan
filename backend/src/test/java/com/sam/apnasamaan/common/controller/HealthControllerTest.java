package com.sam.apnasamaan.common.controller;

import com.sam.apnasamaan.common.config.SecurityConfig;
import com.sam.apnasamaan.common.dto.HealthResponse;
import com.sam.apnasamaan.common.service.SystemHealthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.version=0.1.0",
        "app.support-email=teamapnasamaan@gmail.com",
        "app.cors.allowed-origins=http://localhost:5500"
})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SystemHealthService systemHealthService;

    @Test
    void getHealth_returns200AndSecurityHeaders_whenHealthy() throws Exception {
        when(systemHealthService.checkHealth()).thenReturn(new HealthResponse(
                "UP",
                "Apna Samaan API",
                "0.1.0",
                "UP",
                Instant.parse("2026-10-25T05:30:00Z")
        ));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("Apna Samaan API"))
                .andExpect(jsonPath("$.version").value("0.1.0"))
                .andExpect(jsonPath("$.database").value("UP"));
    }

    @Test
    void getHealth_returns503_whenDatabaseIsDown() throws Exception {
        when(systemHealthService.checkHealth()).thenReturn(new HealthResponse(
                "DOWN",
                "Apna Samaan API",
                "0.1.0",
                "DOWN",
                Instant.parse("2026-10-25T05:30:00Z")
        ));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database").value("DOWN"));
    }

    @Test
    void protectedEndpoint_returns401ConsistentJson_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/shipments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/v1/shipments"))
                .andExpect(jsonPath("$.fieldErrors").isMap());
    }

    @Test
    void corsPreflight_allowsConfiguredOrigin_andBlocksUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "http://localhost:5500")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5500"));

        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "https://malicious.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
