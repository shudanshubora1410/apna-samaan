package com.sam.apnasamaan.shipment.controller;

import com.sam.apnasamaan.auth.security.UserPrincipal;
import com.sam.apnasamaan.common.config.OpenApiConfig;
import com.sam.apnasamaan.common.dto.PagedResponse;
import com.sam.apnasamaan.shipment.dto.DeliveryNoteRequest;
import com.sam.apnasamaan.shipment.dto.DriverJobOfferResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentDetailResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentSummaryResponse;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import com.sam.apnasamaan.shipment.service.DriverDeliveryService;
import com.sam.apnasamaan.shipment.service.DriverMatchingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import java.util.Map;

@RestController
@RequestMapping("/api/v1/driver")
@PreAuthorize("hasRole('DRIVER')")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME)
@Tag(name = "Driver - Jobs & Delivery", description = "Sequential 60s job offers and assigned delivery progression")
public class DriverJobController {

    private final DriverMatchingService driverMatchingService;
    private final DriverDeliveryService driverDeliveryService;

    public DriverJobController(
            DriverMatchingService driverMatchingService,
            DriverDeliveryService driverDeliveryService
    ) {
        this.driverMatchingService = driverMatchingService;
        this.driverDeliveryService = driverDeliveryService;
    }

    @GetMapping("/offers/pending")
    @Operation(summary = "List active 60-second job offers currently waiting for this driver")
    public ResponseEntity<List<DriverJobOfferResponse>> listPendingOffers(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(driverMatchingService.listPendingOffersForDriver(principal));
    }

    @PostMapping("/offers/{offerId}/accept")
    @Operation(summary = "Accept a 60s job offer (transitions shipment SEARCHING_DRIVER -> ASSIGNED)")
    public ResponseEntity<ShipmentDetailResponse> acceptOffer(
            @PathVariable Long offerId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(driverMatchingService.acceptOffer(offerId, principal));
    }

    @PostMapping("/offers/{offerId}/decline")
    @Operation(summary = "Decline a 60s job offer (immediately offers the next nearest eligible driver)")
    public ResponseEntity<Map<String, String>> declineOffer(
            @PathVariable Long offerId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        driverMatchingService.declineOffer(offerId, principal);
        return ResponseEntity.ok(Map.of("message", "Job offer declined"));
    }

    @GetMapping("/shipments/active")
    @Operation(summary = "Get the driver's currently active deliveries (ASSIGNED, PICKED_UP, IN_TRANSIT)")
    public ResponseEntity<List<ShipmentDetailResponse>> getActiveDeliveries(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(driverDeliveryService.getActiveDeliveries(principal));
    }

    @GetMapping("/shipments")
    @Operation(summary = "List driver's assigned/completed shipment history with pagination")
    public ResponseEntity<PagedResponse<ShipmentSummaryResponse>> listDriverShipments(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(name = "status", required = false) ShipmentStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(driverDeliveryService.listDriverShipments(principal, status, page, size));
    }

    @PostMapping("/shipments/{shipmentId}/pickup")
    @Operation(summary = "Mark assigned shipment as PICKED_UP at sender location")
    public ResponseEntity<ShipmentDetailResponse> markPickedUp(
            @PathVariable Long shipmentId,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody(required = false) DeliveryNoteRequest request
    ) {
        return ResponseEntity.ok(driverDeliveryService.markPickedUp(shipmentId, principal, request));
    }

    @PostMapping("/shipments/{shipmentId}/in-transit")
    @Operation(summary = "Mark picked-up shipment as IN_TRANSIT toward dropoff")
    public ResponseEntity<ShipmentDetailResponse> markInTransit(
            @PathVariable Long shipmentId,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody(required = false) DeliveryNoteRequest request
    ) {
        return ResponseEntity.ok(driverDeliveryService.markInTransit(shipmentId, principal, request));
    }

    @PostMapping("/shipments/{shipmentId}/deliver")
    @Operation(summary = "Mark shipment as DELIVERED, release driver availability, and email receipt")
    public ResponseEntity<ShipmentDetailResponse> markDelivered(
            @PathVariable Long shipmentId,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody(required = false) DeliveryNoteRequest request
    ) {
        return ResponseEntity.ok(driverDeliveryService.markDelivered(shipmentId, principal, request));
    }
}
