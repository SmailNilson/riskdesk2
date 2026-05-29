package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.event.FlashCrashPhaseChanged;
import com.riskdesk.domain.orderflow.event.IcebergDetected;
import com.riskdesk.domain.orderflow.event.SpoofingDetected;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.model.FlashCrashThresholds;
import com.riskdesk.domain.orderflow.model.WallEvent;
import com.riskdesk.domain.orderflow.model.WallEvent.WallEventType;
import com.riskdesk.domain.orderflow.model.WallEvent.WallSide;
import com.riskdesk.domain.orderflow.port.FlashCrashConfigPort;
import com.riskdesk.domain.orderflow.port.FootprintPort;
import com.riskdesk.domain.orderflow.port.MarketDepthPort;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayContractResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayNativeClient;
import com.riskdesk.infrastructure.marketdata.ibkr.SubscriptionRegistry;
import com.riskdesk.infrastructure.marketdata.ibkr.TickByTickClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the live wiring of the book-manipulation detectors.
 * <p>
 * These detectors ({@code IcebergDetector}, {@code SpoofingDetector}, {@code FlashCrashFSM})
 * existed for a long time with a full downstream pipeline (events → WebSocket + DB → UI) but
 * were <b>never invoked on the live feed</b>, so {@code /topic/iceberg}, {@code /topic/spoofing}
 * and {@code /topic/flash-crash} silently never emitted. This test fails if that wiring is
 * removed again.
 */
class OrderFlowManipulationWiringTest {

    private OrderFlowProperties properties;
    private MarketDepthPort depthPort;
    private TickDataPort tickDataPort;
    private FlashCrashConfigPort flashCrashConfig;
    private ApplicationEventPublisher eventPublisher;
    private OrderFlowOrchestrator orchestrator;

    private final Instant now = Instant.parse("2026-05-29T14:30:00Z");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new OrderFlowProperties();
        depthPort = mock(MarketDepthPort.class);
        tickDataPort = mock(TickDataPort.class);
        flashCrashConfig = mock(FlashCrashConfigPort.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        ObjectProvider<TickDataPort> tickProvider = mock(ObjectProvider.class);
        when(tickProvider.getIfAvailable()).thenReturn(tickDataPort);
        ObjectProvider<MarketDepthPort> depthProvider = mock(ObjectProvider.class);
        when(depthProvider.getIfAvailable()).thenReturn(depthPort);
        ObjectProvider<FootprintPort> footprintProvider = mock(ObjectProvider.class);

        orchestrator = new OrderFlowOrchestrator(
            mock(IbGatewayNativeClient.class),
            mock(IbGatewayContractResolver.class),
            properties,
            tickProvider,
            mock(SimpMessagingTemplate.class),
            mock(TickLogService.class),
            mock(SubscriptionRegistry.class),
            mock(TickByTickClient.class),
            depthProvider,
            footprintProvider,
            eventPublisher,
            mock(CandleRepositoryPort.class),
            flashCrashConfig
        );
    }

    @Test
    void wallEvents_produceIcebergAndSpoofingEvents() {
        // BID iceberg at 20000 (2 recharge cycles, sizes ~150) + ASK spoof at 20050 (500-lot pulled in 3s).
        List<WallEvent> walls = List.of(
            wall(WallSide.BID, 20000.0, 150, WallEventType.APPEARED, -50),
            wall(WallSide.BID, 20000.0, 150, WallEventType.DISAPPEARED, -40),
            wall(WallSide.BID, 20000.0, 160, WallEventType.APPEARED, -30),
            wall(WallSide.BID, 20000.0, 160, WallEventType.DISAPPEARED, -20),
            wall(WallSide.BID, 20000.0, 155, WallEventType.APPEARED, -10),
            wall(WallSide.ASK, 20050.0, 500, WallEventType.APPEARED, -8),
            wall(WallSide.ASK, 20050.0, 500, WallEventType.DISAPPEARED, -5)
        );
        when(depthPort.recentWallEvents(eq(Instrument.MNQ), any(Duration.class))).thenReturn(walls);

        // avgLevelSize = (1000+1000)/(2*10 rows) = 100 → spoof wall 500 ≥ 3×100; price (mid≈20060) crossed the ASK 20050.
        DepthMetrics depth = new DepthMetrics(
            Instrument.MNQ, 1000, 1000, 0.0,
            20060.0, 20060.25, 0.25, 1,
            null, null, now);
        when(depthPort.currentDepth(Instrument.MNQ)).thenReturn(Optional.of(depth));

        orchestrator.evaluateBookManipulation(now);

        verify(eventPublisher, atLeastOnce()).publishEvent(any(IcebergDetected.class));
        verify(eventPublisher, atLeastOnce()).publishEvent(any(SpoofingDetected.class));
    }

    @Test
    void rescanWithinCooldown_doesNotReemit() {
        List<WallEvent> walls = List.of(
            wall(WallSide.BID, 20000.0, 150, WallEventType.APPEARED, -50),
            wall(WallSide.BID, 20000.0, 150, WallEventType.DISAPPEARED, -40),
            wall(WallSide.BID, 20000.0, 160, WallEventType.APPEARED, -30),
            wall(WallSide.BID, 20000.0, 160, WallEventType.DISAPPEARED, -20),
            wall(WallSide.BID, 20000.0, 155, WallEventType.APPEARED, -10)
        );
        when(depthPort.recentWallEvents(eq(Instrument.MNQ), any(Duration.class))).thenReturn(walls);
        when(depthPort.currentDepth(Instrument.MNQ)).thenReturn(Optional.empty());

        // Two scans back-to-back (well inside the 60s dedup window) → emit once.
        orchestrator.evaluateBookManipulation(now);
        orchestrator.evaluateBookManipulation(now);

        verify(eventPublisher, atLeastOnce()).publishEvent(any(IcebergDetected.class));
        // The second scan of the same still-in-window pattern must NOT double-fire.
        verify(eventPublisher, never()).publishEvent(any(SpoofingDetected.class));
    }

    @Test
    void fastDirectionalMove_triggersFlashCrashTransition() {
        // 100-pt move over 5s at MNQ tick 0.25 → velocity 80 ticks/s (>15); delta 500 (>300);
        // depthImbalance -0.5 (<0.3) → 3/5 conditions → NORMAL → INITIATING transition.
        TickAggregation agg = new TickAggregation(
            Instrument.MNQ, 750, 250, 500, 500, 75.0,
            TickAggregation.TREND_FALLING, false, null,
            now.minusSeconds(5), now, TickAggregation.SOURCE_REAL_TICKS,
            20100.0, 20000.0, 20000.0, 20100.0);
        when(tickDataPort.recentAggregation(eq(Instrument.MNQ), eq(5L))).thenReturn(Optional.of(agg));

        DepthMetrics depth = new DepthMetrics(
            Instrument.MNQ, 500, 1500, -0.5,
            20000.0, 20000.25, 0.25, 1, null, null, now);
        when(depthPort.currentDepth(Instrument.MNQ)).thenReturn(Optional.of(depth));
        when(flashCrashConfig.loadThresholds(Instrument.MNQ)).thenReturn(Optional.empty());

        orchestrator.evaluateFlashCrash(Instrument.MNQ, tickDataPort, now);

        verify(eventPublisher, atLeastOnce()).publishEvent(any(FlashCrashPhaseChanged.class));
    }

    @Test
    void calmMarket_noFlashCrashEvent() {
        TickAggregation calm = new TickAggregation(
            Instrument.MNQ, 20, 18, 2, 2, 52.0,
            TickAggregation.TREND_FLAT, false, null,
            now.minusSeconds(5), now, TickAggregation.SOURCE_REAL_TICKS,
            20000.5, 20000.0, 20000.0, 20000.25);
        when(tickDataPort.recentAggregation(eq(Instrument.MNQ), eq(5L))).thenReturn(Optional.of(calm));

        DepthMetrics depth = new DepthMetrics(
            Instrument.MNQ, 1000, 1000, 0.0,
            20000.0, 20000.25, 0.25, 1, null, null, now);
        when(depthPort.currentDepth(Instrument.MNQ)).thenReturn(Optional.of(depth));
        when(flashCrashConfig.loadThresholds(Instrument.MNQ)).thenReturn(Optional.empty());

        orchestrator.evaluateFlashCrash(Instrument.MNQ, tickDataPort, now);

        verify(eventPublisher, never()).publishEvent(any(FlashCrashPhaseChanged.class));
    }

    @Test
    void volumeSpikeAfterCalmHistory_triggersTransition() {
        // Prime the baseline with calm, low-volume windows (no move → no transition), then send a
        // window whose only "extra" condition is a 20× volume spike vs the prior baseline.
        // depthImbalance 0.5 keeps the "bids fleeing" condition OFF so volume is the deciding 3rd.
        TickAggregation calm = new TickAggregation(
            Instrument.MNQ, 25, 25, 0, 0, 50.0,
            TickAggregation.TREND_FLAT, false, null,
            now.minusSeconds(5), now, TickAggregation.SOURCE_REAL_TICKS,
            20000.0, 20000.0, 20000.0, 20000.0);   // velocity 0, volume 50
        TickAggregation spike = new TickAggregation(
            Instrument.MNQ, 750, 250, 500, 500, 75.0,
            TickAggregation.TREND_FALLING, false, null,
            now.minusSeconds(5), now, TickAggregation.SOURCE_REAL_TICKS,
            20100.0, 20000.0, 20000.0, 20100.0);    // velocity 80, delta 500, volume 1000

        when(tickDataPort.recentAggregation(eq(Instrument.MNQ), eq(5L)))
            .thenReturn(Optional.of(calm), Optional.of(calm), Optional.of(calm), Optional.of(spike));

        DepthMetrics depth = new DepthMetrics(
            Instrument.MNQ, 1500, 500, 0.5,   // imbalance 0.5 → NOT < 0.3, so condition[3] is off
            20000.0, 20000.25, 0.25, 1, null, null, now);
        when(depthPort.currentDepth(Instrument.MNQ)).thenReturn(Optional.of(depth));
        when(flashCrashConfig.loadThresholds(Instrument.MNQ)).thenReturn(Optional.empty());

        orchestrator.evaluateFlashCrash(Instrument.MNQ, tickDataPort, now); // baseline empty
        orchestrator.evaluateFlashCrash(Instrument.MNQ, tickDataPort, now); // hist [50]
        orchestrator.evaluateFlashCrash(Instrument.MNQ, tickDataPort, now); // hist [50,50]
        orchestrator.evaluateFlashCrash(Instrument.MNQ, tickDataPort, now); // spike vs prior mean 50 → 20×

        // velocity + delta + volume-spike = 3/5 → NORMAL → INITIATING. Under the old logic the
        // spike was averaged into its own baseline (1000/287≈3.5 < 4) and this never fired.
        verify(eventPublisher, atLeastOnce()).publishEvent(any(FlashCrashPhaseChanged.class));
    }

    private WallEvent wall(WallSide side, double price, long size, WallEventType type, int secondsOffset) {
        return new WallEvent(Instrument.MNQ, side, price, size, now.plusSeconds(secondsOffset), type);
    }
}
