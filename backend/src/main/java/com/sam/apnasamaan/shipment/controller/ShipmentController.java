package com.sam.apnasamaan.shipment.controller;

import com.sam.apnasamaan.auth.security.UserPrincipal;
import com.sam.apnasamaan.common.dto.PagedResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentCancelRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentCreateRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentDetailResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentQuoteRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentQuoteResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentStatusHistoryResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentSummaryResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentTrackingResponse;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import com.sam.apnasamaan.shipment.service.ShipmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shipments")
@Tag(name = "Shipments", description = "Side-effect-free fare quoting, customer booking, status history, and cancellation")
public class ShipmentController {

    private final ShipmentService shipmentService;

    public ShipmentController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @PostMapping("/quote")
    @Operation(summary = "Calculate deterministic INR fare quote and vehicle suggestion (no database writes)")
    public ResponseEntity<ShipmentQuoteResponse> calculateQuote(
            @Valid @RequestBody ShipmentQuoteRequest request
    ) {
        return ResponseEntity.ok(shipmentService.calculateQuote(request));
    }

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Book a shipment in PENDING_PAYMENT state (server recomputes road distance and INR fare)")
    public ResponseEntity<ShipmentDetailResponse> createShipment(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ShipmentCreateRequest request
    ) {
        ShipmentDetailResponse created = shipmentService.createShipment(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('CUSTOMER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List the authenticated customer's shipments with pagination and optional status filter")
    public ResponseEntity<PagedResponse<ShipmentSummaryResponse>> listMyShipments(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(name = "status", required = false) ShipmentStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(shipmentService.listMyShipments(principal.getId(), status, page, size));
    }

    @GetMapping("/{shipmentId}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'DRIVER', 'ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get full shipment details (owner customer, assigned driver, or admin)")
    public ResponseEntity<ShipmentDetailResponse> getShipmentDetail(
            @PathVariable Long shipmentId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(shipmentService.getShipmentDetail(shipmentId, principal));
    }

    @GetMapping("/{shipmentId}/history")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'DRIVER', 'ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get chronological status audit trail for a shipment")
    public ResponseEntity<List<ShipmentStatusHistoryResponse>> getShipmentHistory(
            @PathVariable Long shipmentId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(shipmentService.getShipmentHistory(shipmentId, principal));
    }

    @GetMapping("/{shipmentId}/tracking")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'DRIVER', 'ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Poll live tracking snapshot (~5s interval: status + assigned driver live GPS)")
    public ResponseEntity<ShipmentTrackingResponse> getShipmentTracking(
            @PathVariable Long shipmentId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(shipmentService.getTrackingSnapshot(shipmentId, principal));
    }

    @PostMapping("/{shipmentId}/cancel")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Cancel a shipment (customers may cancel only before PICKED_UP)")
    public ResponseEntity<ShipmentDetailResponse> cancelShipment(
            @PathVariable Long shipmentId,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ShipmentCancelRequest request
    ) {
        return ResponseEntity.ok(shipmentService.cancelShipment(shipmentId, principal, request));
    }
}
