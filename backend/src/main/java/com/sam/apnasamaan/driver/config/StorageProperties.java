package com.sam.apnasamaan.driver.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        @NotBlank(message = "FILE_STORAGE_DIR must be configured")
        String driverDocumentsDir
) {
}
