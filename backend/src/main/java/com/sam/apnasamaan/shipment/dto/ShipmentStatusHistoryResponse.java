package com.sam.apnasamaan.shipment.dto;

import com.sam.apnasamaan.shipment.entity.ShipmentActorRole;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Single audit entry from shipment_status_history")
public record ShipmentStatusHistoryResponse(
        Long id,
        ShipmentStatus fromStatus,
        ShipmentStatus toStatus,
        ShipmentActorRole changedByRole,
        String changedByName,
        String note,
        Instant createdAt
) {
}
