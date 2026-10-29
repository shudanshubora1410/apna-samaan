package com.sam.apnasamaan.common.service;

import com.sam.apnasamaan.common.config.AppProperties;
import com.sam.apnasamaan.common.dto.HealthResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

@Slf4j
@Service
public class SystemHealthService {

    private static final String SERVICE_NAME = "Apna Samaan API";
    private static final int DB_VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;
    private final AppProperties appProperties;

    public SystemHealthService(DataSource dataSource, AppProperties appProperties) {
        this.dataSource = dataSource;
        this.appProperties = appProperties;
    }

    public HealthResponse checkHealth() {
        boolean dbUp = isDatabaseReachable();
        String overallStatus = dbUp ? "UP" : "DOWN";
        String dbStatus = dbUp ? "UP" : "DOWN";

        return new HealthResponse(
                overallStatus,
                SERVICE_NAME,
                appProperties.version(),
                dbStatus,
                Instant.now()
        );
    }

    private boolean isDatabaseReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException ex) {
            // Log server-side without exposing JDBC URLs or credentials in the HTTP response
            log.warn("Database health check failed: {}", ex.getMessage());
            return false;
        }
    }
}
