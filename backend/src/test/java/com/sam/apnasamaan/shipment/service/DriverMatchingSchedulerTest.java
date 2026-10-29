package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.shipment.config.MatchingProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DriverMatchingSchedulerTest {

    @Mock
    private DriverMatchingService matchingService;

    @Test
    void sweep_invokesMatchingServiceWhenEnabled() {
        DriverMatchingScheduler scheduler = new DriverMatchingScheduler(
                matchingService,
                new MatchingProperties(60, 180, 5000, true)
        );

        scheduler.sweepOfferExpirationsAndSearchTimeouts();

        verify(matchingService).processTimeoutsAndPendingSearches();
    }

    @Test
    void sweep_skipsWhenSchedulerDisabled() {
        DriverMatchingScheduler scheduler = new DriverMatchingScheduler(
                matchingService,
                new MatchingProperties(60, 180, 5000, false)
        );

        scheduler.sweepOfferExpirationsAndSearchTimeouts();

        verify(matchingService, never()).processTimeoutsAndPendingSearches();
    }

    @Test
    void sweep_catchesUnexpectedExceptionsSoSchedulerThreadNeverDies() {
        DriverMatchingScheduler scheduler = new DriverMatchingScheduler(
                matchingService,
                new MatchingProperties(60, 180, 5000, true)
        );
        doThrow(new RuntimeException("transient DB lock")).when(matchingService).processTimeoutsAndPendingSearches();

        assertThatCode(scheduler::sweepOfferExpirationsAndSearchTimeouts).doesNotThrowAnyException();
    }
}
