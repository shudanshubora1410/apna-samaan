package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.shipment.dto.VehicleTypeResponse;
import com.sam.apnasamaan.shipment.entity.VehicleType;
import com.sam.apnasamaan.shipment.repository.VehicleTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class VehicleTypeService {

    private final VehicleTypeRepository vehicleTypeRepository;

    public VehicleTypeService(VehicleTypeRepository vehicleTypeRepository) {
        this.vehicleTypeRepository = vehicleTypeRepository;
    }

    @Transactional(readOnly = true)
    public List<VehicleTypeResponse> listActiveVehicleTypes() {
        return vehicleTypeRepository.findByActiveTrueOrderByBaseFareAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public VehicleType getActiveByCodeOrThrow(String code) {
        return vehicleTypeRepository.findByCodeIgnoreCaseAndActiveTrue(code.trim())
                .orElseThrow(() -> new BadRequestException(
                        "Invalid vehicle type '" + code + "'. Allowed codes: BIKE, CARGO_AUTO, MINI_TRUCK, TRUCK"
                ));
    }

    public VehicleTypeResponse toResponse(VehicleType vt) {
        return new VehicleTypeResponse(
                vt.getId(),
                vt.getCode(),
                vt.getName(),
                vt.getDescription(),
                vt.getMaxWeightKg(),
                vt.getMaxLengthCm(),
                vt.getMaxWidthCm(),
                vt.getMaxHeightCm(),
                vt.getBaseFare(),
                vt.getPerKmRate()
        );
    }
}
