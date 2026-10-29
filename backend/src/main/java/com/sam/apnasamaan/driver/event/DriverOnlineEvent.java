package com.sam.apnasamaan.driver.event;

/**
 * Published when an approved driver goes ONLINE so waiting shipments in that city can match immediately.
 */
public record DriverOnlineEvent(Long driverProfileId, String city) {
}
