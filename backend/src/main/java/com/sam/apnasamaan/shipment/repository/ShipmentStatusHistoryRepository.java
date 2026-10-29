package com.sam.apnasamaan.shipment.repository;

import com.sam.apnasamaan.shipment.entity.ShipmentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ShipmentStatusHistoryRepository extends JpaRepository<ShipmentStatusHistory, Long> {

    @Query("""
            SELECT h FROM ShipmentStatusHistory h
            LEFT JOIN FETCH h.changedByUser
            WHERE h.shipment.id = :shipmentId
            ORDER BY h.createdAt ASC, h.id ASC
            """)
    List<ShipmentStatusHistory> findByShipmentIdWithActor(@Param("shipmentId") Long shipmentId);
}
