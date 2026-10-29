package com.sam.apnasamaan.admin.controller;

import com.sam.apnasamaan.admin.service.AdminShipmentService;
import com.sam.apnasamaan.common.dto.PagedResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentSummaryResponse;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/shipments")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin - Shipments", description = "Admin shipment search, filtering, and operational oversight")
public class AdminShipmentController {

    private final AdminShipmentService adminShipmentService;

    public AdminShipmentController(AdminShipmentService adminShipmentService) {
        this.adminShipmentService = adminShipmentService;
    }

    @GetMapping
    @Operation(summary = "Search and filter shipments by status, tracking code, city, or customer")
    public ResponseEntity<PagedResponse<ShipmentSummaryResponse>> searchShipments(
            @RequestParam(name = "status", required = false) ShipmentStatus status,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(adminShipmentService.searchShipments(status, search, page, size));
    }
}
