package com.sam.apnasamaan.shipment.repository;

import com.sam.apnasamaan.shipment.entity.VehicleType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VehicleTypeRepository extends JpaRepository<VehicleType, Long> {

    List<VehicleType> findByActiveTrueOrderByBaseFareAsc();

    Optional<VehicleType> findByCodeIgnoreCaseAndActiveTrue(String code);
}
