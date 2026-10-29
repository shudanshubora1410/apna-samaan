package com.sam.apnasamaan.admin.controller;

import com.sam.apnasamaan.admin.dto.DriverRejectRequest;
import com.sam.apnasamaan.admin.service.AdminDriverService;
import com.sam.apnasamaan.auth.security.UserPrincipal;
import com.sam.apnasamaan.common.config.OpenApiConfig;
import com.sam.apnasamaan.common.dto.PagedResponse;
import com.sam.apnasamaan.driver.dto.DriverProfileResponse;
import com.sam.apnasamaan.driver.entity.DriverApprovalStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/drivers")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME)
@Tag(name = "Admin - Drivers", description = "Admin review of driver documents, approval, rejection, and deactivation")
public class AdminDriverController {

    private final AdminDriverService adminDriverService;

    public AdminDriverController(AdminDriverService adminDriverService) {
        this.adminDriverService = adminDriverService;
    }

    @GetMapping
    @Operation(
            summary = "List drivers with optional status filter, search query, and pagination",
            description = "Searches across driver name, email, phone, vehicle registration number, and city."
    )
    public ResponseEntity<PagedResponse<DriverProfileResponse>> listDrivers(
            @Parameter(description = "Filter by approval status (PENDING, APPROVED, REJECTED, DEACTIVATED)")
            @RequestParam(required = false) DriverApprovalStatus status,
            @Parameter(description = "Case-insensitive search by name, email, phone, vehicle number, or city")
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(adminDriverService.listDrivers(status, search, page, size));
    }

    @GetMapping("/{driverId}")
    @Operation(
            summary = "Get full driver details and document download links",
            description = "Use each document's `downloadPath` (`/api/v1/driver/documents/{docId}`) with the admin Bearer token to view the uploaded licence or RC."
    )
    public ResponseEntity<DriverProfileResponse> getDriverById(@PathVariable Long driverId) {
        return ResponseEntity.ok(adminDriverService.getDriverById(driverId));
    }

    @PatchMapping("/{driverId}/approve")
    @Operation(summary = "Approve a driver after verifying uploaded licence and RC")
    public ResponseEntity<DriverProfileResponse> approveDriver(
            @PathVariable Long driverId,
            @AuthenticationPrincipal UserPrincipal adminPrincipal
    ) {
        return ResponseEntity.ok(adminDriverService.approveDriver(driverId, adminPrincipal.getId()));
    }

    @PatchMapping("/{driverId}/reject")
    @Operation(summary = "Reject a driver application with a mandatory reason")
    public ResponseEntity<DriverProfileResponse> rejectDriver(
            @PathVariable Long driverId,
            @AuthenticationPrincipal UserPrincipal adminPrincipal,
            @Valid @RequestBody DriverRejectRequest request
    ) {
        return ResponseEntity.ok(adminDriverService.rejectDriver(driverId, adminPrincipal.getId(), request.reason()));
    }

    @PatchMapping("/{driverId}/deactivate")
    @Operation(
            summary = "Deactivate an existing driver",
            description = "Forces the driver offline, disables login, and revokes all active refresh tokens immediately."
    )
    public ResponseEntity<DriverProfileResponse> deactivateDriver(
            @PathVariable Long driverId,
            @AuthenticationPrincipal UserPrincipal adminPrincipal
    ) {
        return ResponseEntity.ok(adminDriverService.deactivateDriver(driverId, adminPrincipal.getId()));
    }
}
