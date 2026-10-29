package com.sam.apnasamaan.common.service;

import com.sam.apnasamaan.common.config.AppProperties;
import com.sam.apnasamaan.common.dto.HealthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemHealthServiceTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    private SystemHealthService systemHealthService;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties(
                "0.1.0",
                "teamapnasamaan@gmail.com",
                new AppProperties.Cors(List.of("http://localhost:5500"))
        );
        systemHealthService = new SystemHealthService(dataSource, props);
    }

    @Test
    void checkHealth_returnsUp_whenDatabaseConnectionIsValid() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        HealthResponse response = systemHealthService.checkHealth();

        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.database()).isEqualTo("UP");
        assertThat(response.service()).isEqualTo("Apna Samaan API");
        assertThat(response.version()).isEqualTo("0.1.0");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void checkHealth_returnsDown_whenDatabaseConnectionIsInvalid() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(false);

        HealthResponse response = systemHealthService.checkHealth();

        assertThat(response.status()).isEqualTo("DOWN");
        assertThat(response.database()).isEqualTo("DOWN");
    }

    @Test
    void checkHealth_returnsDown_whenDataSourceThrowsSqlException() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused"));

        HealthResponse response = systemHealthService.checkHealth();

        assertThat(response.status()).isEqualTo("DOWN");
        assertThat(response.database()).isEqualTo("DOWN");
    }
}
