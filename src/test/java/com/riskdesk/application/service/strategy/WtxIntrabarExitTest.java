package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.strategy.wtx.WtxAction;
import com.riskdesk.domain.engine.strategy.wtx.WtxConfig;
import com.riskdesk.domain.engine.strategy.wtx.WtxEnrichmentSnapshot;
import com.riskdesk.domain.engine.strategy.wtx.WtxExitType;
import com.riskdesk.domain.engine.strategy.wtx.WtxParamOverride;
import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.engine.strategy.wtx.WtxProfile;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignal;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxParamOverridePort;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxSignalHistoryPort;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxStrategyStatePort;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Live intrabar exits: every closed 1m candle must re-check the open position of each WTX panel
 * (legacy and variant) so a protective stop breached mid-panel-bar exits within a minute — the
 * behaviour the validated backtests model with their 1m exit replay. Entries/signals stay on the
 * panel timeframe; the booked exit price is the STOP LEVEL (backtest fill convention); the panel
 * bar closing later on the same breach must NOT exit a second time.
 */
class WtxIntrabarExitTest {

    private static final Instant T_1M = Instant.parse("2026-06-11T14:01:00Z");
    private static final Instant T_10M = Instant.parse("2026-06-11T14:10:00Z");

    private static final WtxStrategyProperties.Variant Z35 =
            new WtxStrategyProperties.Variant("top-train-Z35", "MNQ", "10m", "top-train-z35", "10m-z35");

    private static final class FakeStatePort implements WtxStrategyStatePort {
        final Map<String, WtxStrategyState> store = new HashMap<>();
        @Override public Optional<WtxStrategyState> load(String instrument, String timeframe) {
            return Optional.ofNullable(store.get(instrument + "|" + timeframe));
        }
        @Override public void save(WtxStrategyState state) {
            store.put(state.instrument() + "|" + state.timeframe(), state);
        }
    }

    private static final class FakeOverridePort implements WtxParamOverridePort {
        final Map<String, WtxParamOverride> store = new HashMap<>();
        @Override public WtxParamOverride load(String instrument, String timeframe) {
            return store.getOrDefault(instrument + "|" + timeframe, WtxParamOverride.NONE);
        }
        @Override public void save(String instrument, String timeframe, WtxParamOverride override) {
            store.put(instrument + "|" + timeframe, override == null ? WtxParamOverride.NONE : override);
        }
    }

    private static final class FakeHistoryPort implements WtxSignalHistoryPort {
        final List<WtxSignal> saved = new ArrayList<>();
        @Override public void save(WtxSignal signal) { saved.add(signal); }
        @Override public List<WtxSignal> findRecent(String instrument, int limit) { return List.of(); }
        @Override public List<WtxSignal> findRecent(String instrument, String timeframe, int limit) {
            return List.of();
        }
    }

    private final FakeStatePort statePort = new FakeStatePort();
    private final FakeOverridePort overridePort = new FakeOverridePort();
    private final FakeHistoryPort historyPort = new FakeHistoryPort();
    /** Per-timeframe candle responses (newest first), keyed by timeframe ("1m", "10m", …). */
    private final Map<String, List<Candle>> candlesByTimeframe = new HashMap<>();

    private static Candle candle(String timeframe, Instant ts, double open, double high,
                                 double low, double close) {
        return new Candle(Instrument.MNQ, timeframe, ts,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), 10);
    }

    /** Flat 10m candles for the WaveTrend warm-up — wt ≈ 0, far from the ±53 zones → no entry signal. */
    private static List<Candle> flat10m(int n, double spikeHighOnLast) {
        List<Candle> out = new ArrayList<>();
        Instant t = Instant.parse("2026-06-10T10:00:00Z");
        for (int i = 0; i < n; i++) {
            double high = (i == n - 1 && spikeHighOnLast > 0) ? spikeHighOnLast : 29_001;
            out.add(candle("10m", t.plusSeconds(600L * i), 29_000, high, 28_999, 29_000));
        }
        java.util.Collections.reverse(out); // service expects DESC (newest first)
        return out;
    }

    @SuppressWarnings("unchecked")
    private WtxStrategyService service(List<WtxStrategyProperties.Variant> variants) {
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        WtxEnrichmentBuilder enrichmentBuilder = mock(WtxEnrichmentBuilder.class);
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WtxStrategyProperties properties = mock(WtxStrategyProperties.class);
        ObjectProvider<WtxExecutionBridge> bridgeProvider = mock(ObjectProvider.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        WtxClosePnlSettler settler = mock(WtxClosePnlSettler.class);
        WtxPositionReconciler reconciler = mock(WtxPositionReconciler.class);

        lenient().when(properties.toConfig()).thenReturn(WtxConfig.defaults());
        lenient().when(properties.getInitialEquity()).thenReturn(BigDecimal.valueOf(10_000));
        lenient().when(properties.getVariants()).thenReturn(variants);
        lenient().when(candlePort.findRecentCandles(any(), anyString(), anyInt()))
                .thenAnswer(inv -> candlesByTimeframe.getOrDefault((String) inv.getArgument(1), List.of()));
        lenient().when(enrichmentBuilder.build(anyString(), anyString()))
                .thenReturn(WtxEnrichmentSnapshot.empty());
        lenient().when(settler.settle(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(reconciler.reconcile(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(bridgeProvider.getIfAvailable()).thenReturn(null);

        return new WtxStrategyService(statePort, historyPort, candlePort, enrichmentBuilder, ws,
                properties, bridgeProvider, publisher, settler, reconciler, overridePort);
    }

    /** SESSION_ATR state holding a position: ATR-mode stop distance = 1.4 × ATR(20) = 28 points. */
    private static WtxStrategyState openPosition(String panelKey, WtxPosition side, double entry) {
        return WtxStrategyState.initial("MNQ", panelKey, BigDecimal.valueOf(10_000))
                .withProfile(WtxProfile.SESSION_ATR)
                .withPosition(side, BigDecimal.valueOf(entry), BigDecimal.valueOf(2), BigDecimal.valueOf(20));
    }

    @Test
    void oneMinuteBreach_midPanelBar_exitsPromptly_atTheStopLevel() {
        WtxStrategyService svc = service(List.of());
        statePort.save(openPosition("10m", WtxPosition.SHORT, 29_000));
        // Short stop = 29000 + 28 = 29028; the 1m high breaches it mid-10m-bar.
        candlesByTimeframe.put("1m", List.of(candle("1m", T_1M, 29_020, 29_043.50, 29_015, 29_040)));

        svc.onCandleClosed(new CandleClosed("MNQ", "1m", T_1M));

        WtxStrategyState closed = statePort.store.get("MNQ|10m");
        assertEquals(WtxPosition.FLAT, closed.currentPosition(), "breached stop must exit on the 1m close");
        assertNull(closed.lastCandleTs(), "1m sweep must not touch lastCandleTs (day-reset belongs to the panel bar)");

        assertEquals(1, historyPort.saved.size());
        WtxSignal exit = historyPort.saved.get(0);
        assertEquals("10m", exit.timeframe(), "exit row must land under the owning panel key");
        assertEquals(WtxAction.CLOSE_SHORT, exit.suggestedAction());
        assertEquals(WtxExitType.STOP_LOSS, exit.exitType());
        assertEquals(T_1M, exit.signalTs(), "exit must be stamped at the breaching 1m bar, not the panel close");
        assertEquals(0, BigDecimal.valueOf(29_028).compareTo(exit.price()),
                "booked exit price is the SL level (backtest fill convention), not the bar close");
        assertEquals(0, BigDecimal.valueOf(-112).compareTo(exit.realizedPnl()),
                "realized P&L books at the SL level: -28 pts × $0.50/0.25 tick × 2 contracts");

        // The sweep must never CREATE panel state (5m panel had none).
        assertFalse(statePort.store.containsKey("MNQ|5m"));
    }

    @Test
    void panelCloseOnTheSameBreach_doesNotExitTwice() {
        WtxStrategyService svc = service(List.of());
        statePort.save(openPosition("10m", WtxPosition.SHORT, 29_000));
        candlesByTimeframe.put("1m", List.of(candle("1m", T_1M, 29_020, 29_043.50, 29_015, 29_040)));
        // The 10m bar that closes later carries the same breach in its high.
        candlesByTimeframe.put("10m", flat10m(120, 29_043.50));

        svc.onCandleClosed(new CandleClosed("MNQ", "1m", T_1M));
        svc.onCandleClosed(new CandleClosed("MNQ", "10m", T_10M));

        long exits = historyPort.saved.stream().filter(s -> s.exitType() != null).count();
        assertEquals(1, exits, "the 10m close must not re-exit a position the 1m sweep already closed");
        assertEquals(WtxPosition.FLAT, statePort.store.get("MNQ|10m").currentPosition());
    }

    @Test
    void trailingState_advancesOnEveryOneMinuteCandle() {
        WtxStrategyService svc = service(List.of());
        statePort.save(openPosition("10m", WtxPosition.LONG, 29_000));

        // Bar 1: +20 favorable ≥ activation (0.5R = 14) → trailing arms at 29020 − 2×ATR = 28980.
        candlesByTimeframe.put("1m", List.of(candle("1m", T_1M, 29_005, 29_020, 29_000, 29_010)));
        svc.onCandleClosed(new CandleClosed("MNQ", "1m", T_1M));

        WtxStrategyState afterBar1 = statePort.store.get("MNQ|10m");
        assertEquals(WtxPosition.LONG, afterBar1.currentPosition());
        assertEquals(0, BigDecimal.valueOf(29_020).compareTo(afterBar1.bestFavorablePrice()));
        assertEquals(0, BigDecimal.valueOf(28_980).compareTo(afterBar1.trailingStopPrice()));

        // Bar 2: new high 29050 → MFE and stop ratchet up on the next 1m close.
        Instant t2 = T_1M.plusSeconds(60);
        candlesByTimeframe.put("1m", List.of(candle("1m", t2, 29_010, 29_050, 29_020, 29_045)));
        svc.onCandleClosed(new CandleClosed("MNQ", "1m", t2));

        WtxStrategyState afterBar2 = statePort.store.get("MNQ|10m");
        assertEquals(WtxPosition.LONG, afterBar2.currentPosition());
        assertEquals(0, BigDecimal.valueOf(29_050).compareTo(afterBar2.bestFavorablePrice()));
        assertEquals(0, BigDecimal.valueOf(29_010).compareTo(afterBar2.trailingStopPrice()));
        assertTrue(historyPort.saved.isEmpty(), "no exit signal while the stop is not breached");
    }

    @Test
    void variantPanel_exitsOnOneMinuteBreach_usingItsOwnPresetStop() {
        WtxStrategyService svc = service(List.of(Z35));
        statePort.save(openPosition("10m-z35", WtxPosition.SHORT, 29_000));
        // Z35 preset slAtrMult = 4.0 → stop = 29000 + 80 = 29080; breach needs a bigger spike.
        candlesByTimeframe.put("1m", List.of(candle("1m", T_1M, 29_020, 29_100, 29_015, 29_090)));

        svc.onCandleClosed(new CandleClosed("MNQ", "1m", T_1M));

        assertEquals(WtxPosition.FLAT, statePort.store.get("MNQ|10m-z35").currentPosition());
        assertEquals(1, historyPort.saved.size());
        WtxSignal exit = historyPort.saved.get(0);
        assertEquals("10m-z35", exit.timeframe());
        assertEquals(0, BigDecimal.valueOf(29_080).compareTo(exit.price()),
                "variant exit must use the variant's preset stop distance, not the global one");
        assertFalse(statePort.store.containsKey("MNQ|10m"), "legacy panel state must not be created by the sweep");
    }

    @Test
    void baselineProfile_isNotSweptOnOneMinute() {
        WtxStrategyService svc = service(List.of());
        // BASELINE (initial default) has no ATR exits — even a clear breach must be ignored.
        statePort.save(WtxStrategyState.initial("MNQ", "10m", BigDecimal.valueOf(10_000))
                .withPosition(WtxPosition.SHORT, BigDecimal.valueOf(29_000), BigDecimal.valueOf(2),
                        BigDecimal.valueOf(20)));
        candlesByTimeframe.put("1m", List.of(candle("1m", T_1M, 29_020, 29_100, 29_015, 29_090)));

        svc.onCandleClosed(new CandleClosed("MNQ", "1m", T_1M));

        assertEquals(WtxPosition.SHORT, statePort.store.get("MNQ|10m").currentPosition());
        assertTrue(historyPort.saved.isEmpty());
    }
}
