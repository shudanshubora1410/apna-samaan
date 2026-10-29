package com.sam.apnasamaan.shipment.entity;

/**
 * Lifecycle states of a shipment across booking, payment, driver assignment, and delivery.
 */
public enum ShipmentStatus {
    PENDING_PAYMENT,
    SEARCHING_DRIVER,
    ASSIGNED,
    PICKED_UP,
    IN_TRANSIT,
    DELIVERED,
    CANCELLED,
    FAILED
}
