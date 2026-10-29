package com.sam.apnasamaan;

import com.sam.apnasamaan.common.dto.HealthResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ApnaSamaanApplicationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads_andHealthEndpointReportsDatabaseUp() {
        ResponseEntity<HealthResponse> response = restTemplate.getForEntity("/api/v1/health", HealthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("UP");
        assertThat(response.getBody().database()).isEqualTo("UP");
        assertThat(response.getBody().service()).isEqualTo("Apna Samaan API");
    }

    @Test
    void openApiDocsAndSwaggerUiAreReachable() {
        ResponseEntity<String> openApiResponse = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(openApiResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(openApiResponse.getBody()).contains("Apna Samaan API", "/api/v1/health", "/api/v1/auth/login");

        ResponseEntity<String> swaggerUiResponse = restTemplate.getForEntity("/swagger-ui.html", String.class);
        assertThat(swaggerUiResponse.getStatusCode().is2xxSuccessful()
                || swaggerUiResponse.getStatusCode().is3xxRedirection()).isTrue();
    }
}
