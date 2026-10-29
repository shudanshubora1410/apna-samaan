package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.shipment.config.MatchingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DriverMatchingScheduler {

    private final DriverMatchingService driverMatchingService;
    private final MatchingProperties matchingProperties;

    public DriverMatchingScheduler(
            DriverMatchingService driverMatchingService,
            MatchingProperties matchingProperties
    ) {
        this.driverMatchingService = driverMatchingService;
        this.matchingProperties = matchingProperties;
    }

    @Scheduled(fixedDelayString = "${app.matching.sweep-interval-millis:5000}")
    public void sweepOfferExpirationsAndSearchTimeouts() {
        if (!matchingProperties.schedulerEnabled()) {
            return;
        }
        try {
            driverMatchingService.processTimeoutsAndPendingSearches();
        } catch (Exception ex) {
            log.error("Unexpected error during driver matching sweep: {}", ex.getMessage(), ex);
        }
    }
}
