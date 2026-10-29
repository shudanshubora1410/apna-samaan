package com.sam.apnasamaan.driver.controller;

import com.sam.apnasamaan.auth.security.UserPrincipal;
import com.sam.apnasamaan.common.config.OpenApiConfig;
import com.sam.apnasamaan.driver.dto.DriverAvailabilityRequest;
import com.sam.apnasamaan.driver.dto.DriverDocumentResponse;
import com.sam.apnasamaan.driver.dto.DriverLocationResponse;
import com.sam.apnasamaan.driver.dto.DriverLocationUpdateRequest;
import com.sam.apnasamaan.driver.dto.DriverProfileResponse;
import com.sam.apnasamaan.driver.entity.DriverDocumentType;
import com.sam.apnasamaan.driver.service.DriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/driver")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME)
@Tag(name = "Driver", description = "Driver profile, private document management, and online/offline toggle")
public class DriverController {

    private final DriverService driverService;

    public DriverController(DriverService driverService) {
        this.driverService = driverService;
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(
            summary = "View own driver profile and approval status",
            description = "Returns the authenticated driver's profile, vehicle info, approval status, rejection reason (if any), and uploaded document metadata."
    )
    public ResponseEntity<DriverProfileResponse> getMyProfile(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(driverService.getMyProfile(principal));
    }

    @PutMapping(value = "/documents/{docType}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(
            summary = "Upload or replace a driver document (DRIVING_LICENCE or VEHICLE_RC)",
            description = "Accepts real JPEG, PNG, or PDF up to 5 MB. If the driver was previously REJECTED, replacing a document automatically moves status back to PENDING for re-review."
    )
    public ResponseEntity<DriverDocumentResponse> uploadOrReplaceDocument(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable DriverDocumentType docType,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.ok(driverService.uploadOrReplaceDocument(principal, docType, file));
    }

    @GetMapping("/documents/{documentId}")
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    @Operation(
            summary = "Download/view a private driver document (owner DRIVER or ADMIN only)",
            description = "Streams the stored JPEG, PNG, or PDF from disk after verifying the caller is either the owning driver or an admin."
    )
    public ResponseEntity<Resource> downloadDocument(
            @PathVariable Long documentId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        DriverService.DownloadedDocument doc = driverService.downloadDocument(documentId, principal);

        ContentDisposition contentDisposition = ContentDisposition.inline()
                .filename(doc.originalFilename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.contentType()))
                .contentLength(doc.fileSizeBytes())
                .cacheControl(CacheControl.noStore().mustRevalidate().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(doc.resource());
    }

    @PatchMapping("/availability")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(
            summary = "Toggle driver ONLINE or OFFLINE",
            description = "Only APPROVED drivers with both verification documents on file can switch online=true."
    )
    public ResponseEntity<DriverProfileResponse> updateAvailability(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody DriverAvailabilityRequest request
    ) {
        return ResponseEntity.ok(driverService.updateOnlineStatus(principal, request.online()));
    }

    @PutMapping("/location")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(
            summary = "Report live GPS coordinates (~10s interval while online or on delivery)",
            description = "Updates currentLat, currentLng, and locationUpdatedAt for Haversine matching and customer live tracking."
    )
    public ResponseEntity<DriverLocationResponse> updateLocation(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody DriverLocationUpdateRequest request
    ) {
        return ResponseEntity.ok(driverService.updateLocation(principal, request));
    }
}
