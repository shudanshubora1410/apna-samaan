package com.sam.apnasamaan.common.mail;

import java.time.Instant;

/**
 * Structured payload for notifying the shipper when their shipment is successfully delivered.
 */
public record ShipmentDeliveredMailData(
        String customerEmail,
        String customerName,
        String trackingCode,
        String driverName,
        String vehicleTypeName,
        String vehicleNumber,
        String pickupAddress,
        String pickupCity,
        String dropAddress,
        String dropCity,
        String totalFareInr,
        Instant deliveredAt
) {
}
