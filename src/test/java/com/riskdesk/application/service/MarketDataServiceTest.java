package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.marketdata.port.MarketDataProvider;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayNativeClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.*;

class MarketDataServiceTest {

    @Test
    void fallsBackToLatestStoredDatabasePriceWhenProviderReturnsEmpty() {
        MarketDataProvider marketDataProvider = () -> Map.of();
        PositionService positionService = mock(PositionService.class);
        AlertService alertService = mock(AlertService.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        DxyMarketService dxyMarketService = mock(DxyMarketService.class);

        Candle older10m = candle(Instrument.MCL, "10m", "2026-03-23T10:00:00Z", "101.25");
        Candle newer5m = candle(Instrument.MCL, "5m", "2026-03-23T10:05:00Z", "101.75");

        when(candlePort.findRecentCandles(any(), anyString(), eq(1))).thenReturn(List.of());
        when(candlePort.findRecentCandles(Instrument.MCL, "10m", 1)).thenReturn(List.of(older10m));
        when(candlePort.findRecentCandles(Instrument.MCL, "5m", 1)).thenReturn(List.of(newer5m));

        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);

        MarketDataService service = new MarketDataService(
            marketDataProvider,
            positionService,
            alertService,
            mock(BehaviourAlertService.class),
            candlePort,
            contractRegistry,
            messagingTemplate,
            eventPublisher,
            dxyMarketService,
            nativeProvider(null),
            false,
            120L
        );

        service.pollPrices();

        verify(positionService).updateMarketPrice(Instrument.MCL, new BigDecimal("101.75"));
        verify(alertService, never()).evaluate(any());
        verify(candlePort, never()).save(any());
        verify(dxyMarketService).refreshSyntheticDxy();
        verify(messagingTemplate).convertAndSend(eq("/topic/prices"), (Object) argThat(payload -> {
            if (!(payload instanceof Map<?, ?> map)) return false;
            return "MCL".equals(map.get("instrument"))
                && new BigDecimal("101.75").compareTo(new BigDecimal(String.valueOf(map.get("price")))) == 0
                && "2026-03-23T10:05:00Z".equals(String.valueOf(map.get("timestamp")));
        }));
    }

    @Test
    void accumulate_rollsOverOnlyMatchingInstrumentAndTimeframeBucket() {
        MarketDataProvider marketDataProvider = mock(MarketDataProvider.class);
        PositionService positionService = mock(PositionService.class);
        AlertService alertService = mock(AlertService.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);
        DxyMarketService dxyMarketService = mock(DxyMarketService.class);
        when(contractRegistry.getContractMonth(Instrument.MCL)).thenReturn(Optional.empty());

        MarketDataService service = new MarketDataService(
            marketDataProvider,
            positionService,
            alertService,
            mock(BehaviourAlertService.class),
            candlePort,
            contractRegistry,
            messagingTemplate,
            eventPublisher,
            dxyMarketService,
            nativeProvider(null),
            false,
            120L
        );

        Instant periodOne = Instant.parse("2026-03-30T10:04:00Z");
        Instant periodTwo = Instant.parse("2026-03-30T10:15:00Z");

        ReflectionTestUtils.invokeMethod(service, "accumulate",
                Instrument.MCL, "10m", new BigDecimal("62.40"), periodOne);
        ReflectionTestUtils.invokeMethod(service, "accumulate",
                Instrument.MCL, "1h", new BigDecimal("62.50"), periodOne);
        ReflectionTestUtils.invokeMethod(service, "accumulate",
                Instrument.MCL, "10m", new BigDecimal("62.80"), periodOne);
        ReflectionTestUtils.invokeMethod(service, "accumulate",
                Instrument.MCL, "10m", new BigDecimal("63.10"), periodTwo);

        verify(candlePort, times(1)).save(argThat(candle ->
                candle.getInstrument() == Instrument.MCL
                        && "10m".equals(candle.getTimeframe())
                        && candle.getOpen().compareTo(new BigDecimal("62.40")) == 0
                        && candle.getHigh().compareTo(new BigDecimal("62.80")) == 0
                        && candle.getClose().compareTo(new BigDecimal("62.80")) == 0));
        verify(eventPublisher, times(1)).publishEvent((Object) argThat(event ->
                event instanceof com.riskdesk.domain.marketdata.event.CandleClosed closed
                        && "MCL".equals(closed.instrument())
                        && "10m".equals(closed.timeframe())));
    }

    @Test
    void volume_isTradedContractsFromVolumeDeltas_notPriceUpdateCount() {
        MarketDataService service = newService();

        // Monday 2026-03-30 10:0xZ = 06:0x EDT — market open, outside the maintenance window.
        Instant periodOne = Instant.parse("2026-03-30T10:04:00Z");
        Instant periodTwo = Instant.parse("2026-03-30T10:15:00Z");

        ReflectionTestUtils.invokeMethod(service, "accumulate",
                Instrument.MCL, "10m", new BigDecimal("62.40"), periodOne);
        service.onLiveVolumeUpdate(Instrument.MCL, 150L, periodOne);
        ReflectionTestUtils.invokeMethod(service, "accumulate",
                Instrument.MCL, "10m", new BigDecimal("62.80"), periodOne.plusSeconds(30));
        service.onLiveVolumeUpdate(Instrument.MCL, 50L, periodOne.plusSeconds(60));
        ReflectionTestUtils.invokeMethod(service, "accumulate",
                Instrument.MCL, "10m", new BigDecimal("63.10"), periodTwo);

        verify(candlePort, times(1)).save(argThat(candle ->
                "10m".equals(candle.getTimeframe()) && candle.getVolume() == 200L));
    }

    @Test
    void volume_arrivingBeforeFirstPriceOfBar_isClaimedWhenBarOpens() {
        MarketDataService service = newService();

        Instant periodOne = Instant.parse("2026-03-30T10:04:00Z");
        Instant periodTwo = Instant.parse("2026-03-30T10:15:00Z");

        // Volume tick precedes the bar's first price tick.
        service.onLiveVolumeUpdate(Instrument.MCL, 75L, periodOne);
        ReflectionTestUtils.invokeMethod(service, "accumulate",
                Instrument.MCL, "10m", new BigDecimal("62.40"), periodOne.plusSeconds(5));
        ReflectionTestUtils.invokeMethod(service, "accumulate",
                Instrument.MCL, "10m", new BigDecimal("63.10"), periodTwo);

        verify(candlePort, times(1)).save(argThat(candle ->
                "10m".equals(candle.getTimeframe()) && candle.getVolume() == 75L));
    }

    @Test
    void volume_isZeroWhenNoVolumeDeltasArrive_priceUpdatesDoNotInflateIt() {
        MarketDataService service = newService();

        Instant periodOne = Instant.parse("2026-03-30T10:04:00Z");
        Instant periodTwo = Instant.parse("2026-03-30T10:15:00Z");

        for (int i = 0; i < 25; i++) {
            ReflectionTestUtils.invokeMethod(service, "accumulate",
                    Instrument.MCL, "10m", new BigDecimal("62.40").add(BigDecimal.valueOf(i, 2)),
                    periodOne.plusSeconds(i));
        }
        ReflectionTestUtils.invokeMethod(service, "accumulate",
                Instrument.MCL, "10m", new BigDecimal("63.10"), periodTwo);

        verify(candlePort, times(1)).save(argThat(candle ->
                "10m".equals(candle.getTimeframe()) && candle.getVolume() == 0L));
    }

    private CandleRepositoryPort candlePort;

    private MarketDataService newService() {
        candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);
        when(contractRegistry.getContractMonth(any())).thenReturn(Optional.empty());
        return new MarketDataService(
            mock(MarketDataProvider.class),
            mock(PositionService.class),
            mock(AlertService.class),
            mock(BehaviourAlertService.class),
            candlePort,
            contractRegistry,
            mock(SimpMessagingTemplate.class),
            mock(ApplicationEventPublisher.class),
            mock(DxyMarketService.class),
            nativeProvider(null),
            false,
            120L
        );
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<IbGatewayNativeClient> nativeProvider(IbGatewayNativeClient client) {
        ObjectProvider<IbGatewayNativeClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    // -------------------------------------------------------------------------
    // P1.1 — per-instrument isolation in pollPrices()
    // -------------------------------------------------------------------------

    @Test
    void pollPrices_oneInstrumentThrows_doesNotAbortPollOrSkipDxy() {
        // MGC and MCL both have a live price; updating MCL's position throws. The loop must still
        // update MGC and still refresh DXY, and pollPrices() itself must not propagate the exception
        // (which under a propagating error handler would be the only way to cancel the @Scheduled task).
        MarketDataProvider marketDataProvider = () -> Map.of(
            Instrument.MCL, new BigDecimal("62.40"),
            Instrument.MGC, new BigDecimal("2400.0")
        );
        PositionService positionService = mock(PositionService.class);
        doThrow(new RuntimeException("boom-MCL")).when(positionService).updateMarketPrice(eq(Instrument.MCL), any());
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);
        when(contractRegistry.getContractMonth(any())).thenReturn(Optional.empty());
        DxyMarketService dxyMarketService = mock(DxyMarketService.class);

        MarketDataService service = new MarketDataService(
            marketDataProvider,
            positionService,
            mock(AlertService.class),
            mock(BehaviourAlertService.class),
            candlePort,
            contractRegistry,
            mock(SimpMessagingTemplate.class),
            mock(ApplicationEventPublisher.class),
            dxyMarketService,
            nativeProvider(null),
            false,
            120L
        );

        service.pollPrices();   // must not throw

        verify(positionService).updateMarketPrice(eq(Instrument.MGC), any());  // sibling still processed
        verify(dxyMarketService).refreshSyntheticDxy();                        // tail still ran
    }

    // -------------------------------------------------------------------------
    // P0.3 — price-feed freshness watchdog (evaluatePriceFeedFreshness)
    // -------------------------------------------------------------------------

    // Monday 2026-03-30 14:00:00Z = 10:00 EDT — market open, outside both maintenance windows.
    private static final Instant OPEN = Instant.parse("2026-03-30T14:00:00Z");

    @Test
    void watchdog_staleWhileConnected_forcesReconnect() {
        IbGatewayNativeClient client = mock(IbGatewayNativeClient.class);
        when(client.isConnected()).thenReturn(true);
        MarketDataService service = newWatchdogService(client, 120L);
        ReflectionTestUtils.setField(service, "lastLiveTickAt", OPEN.minusSeconds(200));

        service.evaluatePriceFeedFreshness(OPEN, client);

        verify(client).forceReconnect(anyString());
    }

    @Test
    void watchdog_freshTick_doesNotReconnect() {
        IbGatewayNativeClient client = mock(IbGatewayNativeClient.class);
        when(client.isConnected()).thenReturn(true);
        MarketDataService service = newWatchdogService(client, 120L);
        ReflectionTestUtils.setField(service, "lastLiveTickAt", OPEN.minusSeconds(10));

        service.evaluatePriceFeedFreshness(OPEN, client);

        verify(client, never()).forceReconnect(anyString());
    }

    @Test
    void watchdog_notConnected_doesNotReconnect() {
        IbGatewayNativeClient client = mock(IbGatewayNativeClient.class);
        when(client.isConnected()).thenReturn(false);
        MarketDataService service = newWatchdogService(client, 120L);
        ReflectionTestUtils.setField(service, "lastLiveTickAt", OPEN.minusSeconds(500));

        service.evaluatePriceFeedFreshness(OPEN, client);

        verify(client, never()).forceReconnect(anyString());
    }

    @Test
    void watchdog_neverWarmedUp_doesNotReconnect() {
        IbGatewayNativeClient client = mock(IbGatewayNativeClient.class);
        when(client.isConnected()).thenReturn(true);
        MarketDataService service = newWatchdogService(client, 120L);
        // lastLiveTickAt left null

        service.evaluatePriceFeedFreshness(OPEN, client);

        verify(client, never()).forceReconnect(anyString());
    }

    @Test
    void watchdog_duringStandardMaintenance_doesNotReconnect() {
        IbGatewayNativeClient client = mock(IbGatewayNativeClient.class);
        when(client.isConnected()).thenReturn(true);
        MarketDataService service = newWatchdogService(client, 120L);
        // Monday 21:30Z = 17:30 EDT — inside the 17:00–18:00 ET standard maintenance halt.
        Instant maint = Instant.parse("2026-03-30T21:30:00Z");
        ReflectionTestUtils.setField(service, "lastLiveTickAt", maint.minusSeconds(500));

        service.evaluatePriceFeedFreshness(maint, client);

        verify(client, never()).forceReconnect(anyString());
    }

    @Test
    void watchdog_weekend_doesNotReconnect() {
        IbGatewayNativeClient client = mock(IbGatewayNativeClient.class);
        when(client.isConnected()).thenReturn(true);
        MarketDataService service = newWatchdogService(client, 120L);
        // Saturday 2026-03-28 14:00Z — market closed.
        Instant weekend = Instant.parse("2026-03-28T14:00:00Z");
        ReflectionTestUtils.setField(service, "lastLiveTickAt", weekend.minusSeconds(5000));

        service.evaluatePriceFeedFreshness(weekend, client);

        verify(client, never()).forceReconnect(anyString());
    }

    private MarketDataService newWatchdogService(IbGatewayNativeClient client, long stalenessSeconds) {
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);
        when(contractRegistry.getContractMonth(any())).thenReturn(Optional.empty());
        return new MarketDataService(
            mock(MarketDataProvider.class),
            mock(PositionService.class),
            mock(AlertService.class),
            mock(BehaviourAlertService.class),
            mock(CandleRepositoryPort.class),
            contractRegistry,
            mock(SimpMessagingTemplate.class),
            mock(ApplicationEventPublisher.class),
            mock(DxyMarketService.class),
            nativeProvider(client),
            true,
            stalenessSeconds
        );
    }

    private static Candle candle(Instrument instrument, String timeframe, String timestamp, String close) {
        BigDecimal price = new BigDecimal(close);
        return new Candle(
            instrument,
            timeframe,
            Instant.parse(timestamp),
            price,
            price,
            price,
            price,
            1L
        );
    }
}
