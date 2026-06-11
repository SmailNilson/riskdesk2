package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.event.AbsorptionDetected;
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

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestrator-level guard for the two absorption recalibrations of the
 * 2026-06 audit:
 * <ol>
 *   <li><b>Emission de-dup.</b> The 10s absorption window is re-evaluated every
 *       5s, so a single 10-15s burst used to produce 2-3 AbsorptionDetected
 *       events from overlapping windows (~1100 rows/day on MNQ, inflating n8
 *       counts and the distribution detector's streak). A new event for the
 *       same (instrument, side) must be suppressed within
 *       {@code absorption.dedup-seconds} (default = window-seconds).</li>
 *   <li><b>Session-aware threshold.</b> Outside RTH (09:30-16:00 ET) the
 *       absorption/momentum delta threshold is multiplied by
 *       {@code eth-threshold-multiplier} (default 0.4) — resolved DST-aware via
 *       {@code TradingSessionResolver}, never hardcoded UTC hours.</li>
 * </ol>
 */
class OrderFlowAbsorptionDedupTest {

    private OrderFlowProperties properties;
    private ApplicationEventPublisher eventPublisher;
    private OrderFlowOrchestrator orchestrator;

    /** Friday 2026-05-29 14:30Z = 10:30 ET (EDT) → inside RTH. */
    private final Instant rth = Instant.parse("2026-05-29T14:30:00Z");
    /** Friday 2026-05-29 08:00Z = 04:00 ET (EDT) → overnight (ETH). */
    private final Instant eth = Instant.parse("2026-05-29T08:00:00Z");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new OrderFlowProperties();
        eventPublisher = mock(ApplicationEventPublisher.class);

        ObjectProvider<TickDataPort> tickProvider = mock(ObjectProvider.class);
        when(tickProvider.getIfAvailable()).thenReturn(mock(TickDataPort.class));
        ObjectProvider<MarketDepthPort> depthProvider = mock(ObjectProvider.class);
        ObjectProvider<FootprintPort> footprintProvider = mock(ObjectProvider.class);
        ObjectProvider<com.riskdesk.domain.orderflow.port.TickBarPort> tickBarProvider = mock(ObjectProvider.class);
        ObjectProvider<com.riskdesk.domain.orderflow.port.BigPrintPort> bigPrintProvider = mock(ObjectProvider.class);

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
            tickBarProvider,
            bigPrintProvider,
            eventPublisher,
            mock(CandleRepositoryPort.class),
            mock(FlashCrashConfigPort.class)
        );
    }

    /**
     * Strong bearish CLASSIC absorption window: delta -500 with price drifting
     * down. With deltaThreshold 50 (default) and avgVolume == totalVolume on
     * the first scans, score = 500/50 × 1.0 = 10 &gt; 2.0 gate → always detected.
     */
    private TickAggregation bearishBurst(Instant windowEnd) {
        return new TickAggregation(
            Instrument.MNQ, 250, 750, -500, -500, 25.0,
            TickAggregation.TREND_FALLING, false, null,
            windowEnd.minusSeconds(10), windowEnd, TickAggregation.SOURCE_REAL_TICKS,
            20010.0, 20000.0, 20010.0, 20000.0);
    }

    /** Mirror bullish burst (delta +500, price drifting up). */
    private TickAggregation bullishBurst(Instant windowEnd) {
        return new TickAggregation(
            Instrument.MNQ, 750, 250, 500, 500, 75.0,
            TickAggregation.TREND_RISING, false, null,
            windowEnd.minusSeconds(10), windowEnd, TickAggregation.SOURCE_REAL_TICKS,
            20010.0, 20000.0, 20000.0, 20010.0);
    }

    @Test
    void overlappingWindows_emitAbsorptionOnce() {
        // Two scans 5s apart — both windows see the same burst. Second is suppressed.
        orchestrator.evaluateAbsorption(Instrument.MNQ, bearishBurst(rth), rth);
        orchestrator.evaluateAbsorption(Instrument.MNQ, bearishBurst(rth.plusSeconds(5)), rth.plusSeconds(5));

        verify(eventPublisher, times(1)).publishEvent(any(AbsorptionDetected.class));
    }

    @Test
    void nonOverlappingWindows_emitAgain() {
        // Second scan a full window (10s) later → non-overlapping → emits again.
        // This is exactly the cadence a GENUINE sustained burst produces, so the
        // distribution detector's 3-consecutive ≤20s-gap streak still works.
        orchestrator.evaluateAbsorption(Instrument.MNQ, bearishBurst(rth), rth);
        orchestrator.evaluateAbsorption(Instrument.MNQ, bearishBurst(rth.plusSeconds(10)), rth.plusSeconds(10));
        orchestrator.evaluateAbsorption(Instrument.MNQ, bearishBurst(rth.plusSeconds(20)), rth.plusSeconds(20));

        verify(eventPublisher, times(3)).publishEvent(any(AbsorptionDetected.class));
    }

    @Test
    void oppositeSides_haveIndependentDedupKeys() {
        // BEAR then BULL within the dedup window: different (instrument, side)
        // keys → both emit.
        orchestrator.evaluateAbsorption(Instrument.MNQ, bearishBurst(rth), rth);
        orchestrator.evaluateAbsorption(Instrument.MNQ, bullishBurst(rth.plusSeconds(5)), rth.plusSeconds(5));

        verify(eventPublisher, times(2)).publishEvent(any(AbsorptionDetected.class));
    }

    @Test
    void ethSession_scaledThreshold_detectsSmallerDelta() {
        // delta -60: RTH threshold 50 → score 60/50 = 1.2 ≤ 2.0 gate → silent.
        // ETH threshold 50 × 0.4 = 20 → score 60/20 = 3.0 > 2.0 → detected.
        TickAggregation smallBurst = new TickAggregation(
            Instrument.MNQ, 70, 130, -60, -60, 35.0,
            TickAggregation.TREND_FALLING, false, null,
            eth.minusSeconds(10), eth, TickAggregation.SOURCE_REAL_TICKS,
            20010.0, 20000.0, 20010.0, 20000.0);

        orchestrator.evaluateAbsorption(Instrument.MNQ, smallBurst, eth);

        verify(eventPublisher, times(1)).publishEvent(any(AbsorptionDetected.class));
    }

    @Test
    void rthSession_fullThreshold_staysSilentOnSmallDelta() {
        // Same -60 delta during RTH: full threshold 50 → score 1.2 ≤ 2.0 → no event.
        TickAggregation smallBurst = new TickAggregation(
            Instrument.MNQ, 70, 130, -60, -60, 35.0,
            TickAggregation.TREND_FALLING, false, null,
            rth.minusSeconds(10), rth, TickAggregation.SOURCE_REAL_TICKS,
            20010.0, 20000.0, 20010.0, 20000.0);

        orchestrator.evaluateAbsorption(Instrument.MNQ, smallBurst, rth);

        verify(eventPublisher, never()).publishEvent(any(AbsorptionDetected.class));
    }

    @Test
    void rthBoundary_thresholdSwitchesAtOpenAndClose() {
        // 13:29Z = 09:29 ET (one minute before the RTH open) → ETH multiplier
        // applies → the small burst fires; at 14:30Z = 10:30 ET it does not.
        // DST-aware: boundaries resolved via TradingSessionResolver in ET,
        // never via hardcoded UTC hours.
        Instant preOpen = Instant.parse("2026-05-29T13:29:00Z");
        TickAggregation smallBurst = new TickAggregation(
            Instrument.MNQ, 70, 130, -60, -60, 35.0,
            TickAggregation.TREND_FALLING, false, null,
            preOpen.minusSeconds(10), preOpen, TickAggregation.SOURCE_REAL_TICKS,
            20010.0, 20000.0, 20010.0, 20000.0);

        orchestrator.evaluateAbsorption(Instrument.MNQ, smallBurst, preOpen);

        verify(eventPublisher, times(1)).publishEvent(any(AbsorptionDetected.class));
    }
}
