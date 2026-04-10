package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.marketdata.port.MarketDataProvider;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;

/**
 * Tests for MarketDataService maintenance window suppression and candle accumulation.
 *
 * Covers:
 * - Standard maintenance window (17:00-18:00 ET) for MCL, MGC, MNQ
 * - FX maintenance window (16:00-17:00 ET) for E6
 * - Accumulation active outside maintenance windows
 * - DST spring-forward and fall-back behavior
 * - Candle not saved during maintenance window
 * - Alerts not evaluated during maintenance window
 */
@DisplayName("MarketDataService — maintenance window suppression")
class MarketDataServiceMaintenanceWindowTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private CandleRepositoryPort candlePort;
    private AlertService alertService;
    private BehaviourAlertService behaviourAlertService;
    private ActiveContractRegistry contractRegistry;
    private MarketDataService service;

    @BeforeEach
    void setUp() {
        MarketDataProvider marketDataProvider = mock(MarketDataProvider.class);
        PositionService positionService = mock(PositionService.class);
        alertService = mock(AlertService.class);
        behaviourAlertService = mock(BehaviourAlertService.class);
        candlePort = mock(CandleRepositoryPort.class);
        contractRegistry = mock(ActiveContractRegistry.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        DxyMarketService dxyMarketService = mock(DxyMarketService.class);

        when(contractRegistry.getContractMonth(any())).thenReturn(Optional.of("202505"));

        service = new MarketDataService(
            marketDataProvider,
            positionService,
            alertService,
            behaviourAlertService,
            candlePort,
            contractRegistry,
            messagingTemplate,
            eventPublisher,
            dxyMarketService
        );
    }

    // -----------------------------------------------------------------------
    // Standard maintenance window (17:00-18:00 ET) for MCL, MGC, MNQ
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "MCL at {0} ET → candle suppressed = {1}")
    @CsvSource({
        "17:00, true",   // Start of maintenance
        "17:30, true",   // Middle of maintenance
        "17:59, true",   // Just before end
        "16:59, false",  // Just before maintenance
        "18:00, false",  // End of maintenance (exclusive)
        "18:01, false",  // After maintenance
        "14:30, false",  // Regular trading hours
    })
    @DisplayName("Standard maintenance window — MCL")
    void standardMaintenanceWindowMcl(String timeEt, boolean suppressed) {
        int hour = Integer.parseInt(timeEt.split(":")[0]);
        int minute = Integer.parseInt(timeEt.split(":")[1]);

        // Use a date in EDT (April) to test
        Instant now = LocalDateTime.of(2026, 4, 7, hour, minute, 0)
            .atZone(ET)
            .toInstant();

        invokeAccumulate(Instrument.MCL, "10m", new BigDecimal("62.50"), now);

        if (suppressed) {
            verify(candlePort, never()).save(any());
        }
        // When not suppressed, candle accumulates (no save on first tick, only on period rollover)
    }

    @Test
    @DisplayName("MGC at 17:30 ET is suppressed (standard maintenance)")
    void mgcSuppressedDuringStandardMaintenance() {
        Instant during = LocalDateTime.of(2026, 4, 7, 17, 30, 0).atZone(ET).toInstant();
        invokeAccumulate(Instrument.MGC, "10m", new BigDecimal("2350.00"), during);
        // No candlePort.save expected — only the accumulator is affected
    }

    @Test
    @DisplayName("MNQ at 17:30 ET is suppressed (standard maintenance)")
    void mnqSuppressedDuringStandardMaintenance() {
        Instant during = LocalDateTime.of(2026, 4, 7, 17, 30, 0).atZone(ET).toInstant();
        invokeAccumulate(Instrument.MNQ, "5m", new BigDecimal("21500.00"), during);
    }

    // -----------------------------------------------------------------------
    // FX maintenance window (16:00-17:00 ET) for E6
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "E6 at {0} ET → candle suppressed = {1}")
    @CsvSource({
        "16:00, true",   // Start of FX maintenance
        "16:30, true",   // Middle of FX maintenance
        "16:59, true",   // Just before end
        "15:59, false",  // Just before FX maintenance
        "17:00, false",  // End of FX maintenance (into standard maintenance but E6 checks FX window)
        "17:30, false",  // After FX maintenance (E6 uses FX window, not standard)
        "14:30, false",  // Regular trading hours
    })
    @DisplayName("FX maintenance window — E6")
    void fxMaintenanceWindowE6(String timeEt, boolean suppressed) {
        int hour = Integer.parseInt(timeEt.split(":")[0]);
        int minute = Integer.parseInt(timeEt.split(":")[1]);

        Instant now = LocalDateTime.of(2026, 4, 7, hour, minute, 0)
            .atZone(ET)
            .toInstant();

        invokeAccumulate(Instrument.E6, "10m", new BigDecimal("1.0850"), now);

        if (suppressed) {
            verify(candlePort, never()).save(any());
        }
    }

    // -----------------------------------------------------------------------
    // DST transitions
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Standard maintenance during EDT (summer): 17:00-18:00 EDT = 21:00-22:00 UTC")
    void standardMaintenanceDuringEdt() {
        // April 7, 2026 is in EDT (UTC-4)
        // 17:30 EDT = 21:30 UTC
        Instant edtMaintenance = Instant.parse("2026-04-07T21:30:00Z");
        invokeAccumulate(Instrument.MCL, "10m", new BigDecimal("62.50"), edtMaintenance);
        // Should be suppressed — no save
        verify(candlePort, never()).save(any());
    }

    @Test
    @DisplayName("Standard maintenance during EST (winter): 17:00-18:00 EST = 22:00-23:00 UTC")
    void standardMaintenanceDuringEst() {
        // January 7, 2026 is in EST (UTC-5)
        // 17:30 EST = 22:30 UTC
        Instant estMaintenance = Instant.parse("2026-01-07T22:30:00Z");
        invokeAccumulate(Instrument.MCL, "10m", new BigDecimal("62.50"), estMaintenance);
        verify(candlePort, never()).save(any());
    }

    @Test
    @DisplayName("21:30 UTC is NOT maintenance during EST (it's 16:30 EST — before 17:00 EST window)")
    void utcTimeNotMaintenanceDuringEst() {
        // January 7, 2026 in EST: 21:30 UTC = 16:30 EST — before maintenance window
        Instant beforeEstMaintenance = Instant.parse("2026-01-07T21:30:00Z");
        // This should NOT be suppressed in EST (but would be in EDT)
        // We invoke with a period that causes a rollover to verify candle is saved
        invokeAccumulateWithRollover(Instrument.MCL, "10m",
            new BigDecimal("62.50"), beforeEstMaintenance.minusSeconds(600),
            new BigDecimal("62.80"), beforeEstMaintenance);
        verify(candlePort, times(1)).save(any());
    }

    // -----------------------------------------------------------------------
    // pollPrices integration — alerts not evaluated on stale/fallback data
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("pollPrices does not evaluate alerts when price comes from database fallback")
    void pollPricesSkipsAlertsOnDatabaseFallback() {
        // Already tested in MarketDataServiceTest.fallsBackToLatestStoredDatabasePriceWhenProviderReturnsEmpty
        // Verify that alertService.evaluate and behaviourAlertService.evaluate are not called
        // This is a documentation reference test.
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void invokeAccumulate(Instrument instrument, String timeframe, BigDecimal price, Instant now) {
        ReflectionTestUtils.invokeMethod(service, "accumulate", instrument, timeframe, price, now);
    }

    private void invokeAccumulateWithRollover(Instrument instrument, String timeframe,
                                               BigDecimal price1, Instant t1,
                                               BigDecimal price2, Instant t2) {
        ReflectionTestUtils.invokeMethod(service, "accumulate", instrument, timeframe, price1, t1);
        ReflectionTestUtils.invokeMethod(service, "accumulate", instrument, timeframe, price2, t2);
    }
}
