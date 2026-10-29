package com.sam.apnasamaan.driver.dto;

import com.sam.apnasamaan.driver.entity.DriverDocumentType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Safe document metadata — never exposes internal disk paths or raw UUID filenames.
 */
@Schema(description = "Metadata for an uploaded driver verification document")
public record DriverDocumentResponse(
        @Schema(example = "10") Long id,
        @Schema(example = "DRIVING_LICENCE") DriverDocumentType docType,
        @Schema(example = "rajesh_dl.pdf") String originalFilename,
        @Schema(example = "application/pdf") String contentType,
        @Schema(example = "245812") long fileSizeBytes,
        @Schema(example = "2026-10-25T06:00:00Z") Instant uploadedAt,
        @Schema(description = "Authenticated endpoint to view/download this document (owner driver or admin only)",
                example = "/api/v1/driver/documents/10")
        String downloadPath
) {
}
