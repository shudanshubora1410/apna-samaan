package com.sam.apnasamaan.common.mail;

/**
 * Structured payload for notifying the shipper when a driver accepts their shipment.
 */
public record ShipmentAssignedMailData(
        String customerEmail,
        String customerName,
        String trackingCode,
        String driverName,
        String driverPhone,
        String vehicleTypeName,
        String vehicleNumber,
        String pickupAddress,
        String pickupCity,
        String dropAddress,
        String dropCity
) {
}
