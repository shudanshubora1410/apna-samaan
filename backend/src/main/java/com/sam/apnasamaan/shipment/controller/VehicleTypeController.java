package com.sam.apnasamaan.shipment.controller;

import com.sam.apnasamaan.shipment.dto.VehicleTypeResponse;
import com.sam.apnasamaan.shipment.service.VehicleTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vehicle-types")
@Tag(name = "Vehicle Types", description = "Public catalog of delivery vehicle tiers and INR rate cards")
public class VehicleTypeController {

    private final VehicleTypeService vehicleTypeService;

    public VehicleTypeController(VehicleTypeService vehicleTypeService) {
        this.vehicleTypeService = vehicleTypeService;
    }

    @GetMapping
    @Operation(
            summary = "List active vehicle types",
            description = "Returns the 4 seeded vehicle tiers (Bike, Cargo Auto, Mini Truck, Truck) ordered by base fare."
    )
    public ResponseEntity<List<VehicleTypeResponse>> listActiveVehicleTypes() {
        return ResponseEntity.ok(vehicleTypeService.listActiveVehicleTypes());
    }
}
