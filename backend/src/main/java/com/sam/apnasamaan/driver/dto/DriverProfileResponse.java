package com.sam.apnasamaan.driver.dto;

import com.sam.apnasamaan.driver.entity.DriverApprovalStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Driver profile, vehicle details, approval status, and document metadata")
public record DriverProfileResponse(
        @Schema(example = "1") Long driverProfileId,
        @Schema(example = "5") Long userId,
        @Schema(example = "Rajesh Kumar") String name,
        @Schema(example = "rajesh.driver@example.com") String email,
        @Schema(example = "9811223344") String phone,
        @Schema(example = "MINI_TRUCK") String vehicleTypeCode,
        @Schema(example = "Mini Truck") String vehicleTypeName,
        @Schema(example = "DL01AB1234") String vehicleNumber,
        @Schema(example = "Delhi") String city,
        @Schema(example = "PENDING") DriverApprovalStatus approvalStatus,
        @Schema(example = "null") String rejectionReason,
        @Schema(example = "false") boolean online,
        @Schema(example = "true") boolean available,
        @Schema(example = "true") boolean accountEnabled,
        List<DriverDocumentResponse> documents,
        Instant reviewedAt,
        Instant createdAt
) {
}
