package com.sam.apnasamaan.shipment.event;

/**
 * Published synchronously when payment capture transitions a shipment to SEARCHING_DRIVER.
 */
public record ShipmentReadyForMatchingEvent(Long shipmentId) {
}
