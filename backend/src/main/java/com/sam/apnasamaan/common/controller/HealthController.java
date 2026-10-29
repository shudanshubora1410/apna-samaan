package com.sam.apnasamaan.common.controller;

import com.sam.apnasamaan.common.dto.HealthResponse;
import com.sam.apnasamaan.common.service.SystemHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "System", description = "Service readiness and health verification")
public class HealthController {

    private final SystemHealthService systemHealthService;

    public HealthController(SystemHealthService systemHealthService) {
        this.systemHealthService = systemHealthService;
    }

    @GetMapping
    @Operation(
            summary = "Check API and database health",
            description = "Returns HTTP 200 when both the Spring Boot application and MySQL connection pool are healthy, or HTTP 503 if MySQL is unreachable."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Application and database are healthy",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = HealthResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "status": "UP",
                                      "service": "Apna Samaan API",
                                      "version": "1.0.0",
                                      "database": "UP",
                                      "timestamp": "2026-10-25T05:30:00Z"
                                    }
                                    """)
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Database connection is unavailable",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = HealthResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "status": "DOWN",
                                      "service": "Apna Samaan API",
                                      "version": "1.0.0",
                                      "database": "DOWN",
                                      "timestamp": "2026-10-25T05:30:00Z"
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<HealthResponse> getHealth() {
        HealthResponse response = systemHealthService.checkHealth();
        HttpStatus status = "UP".equals(response.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }
}
