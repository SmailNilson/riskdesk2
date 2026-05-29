package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiPosition;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignalRecord;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.WtxRsiStrategyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WtxRsiStrategyServiceTest {

    private InMemoryWtxRsiPorts.State statePort;
    private InMemoryWtxRsiPorts.History historyPort;
    private CandleRepositoryPort candlePort;
    private WtxRsiStrategyProperties properties;
    private SimpMessagingTemplate ws;
    private RecordingWtxRsiBridge bridge;
    private ObjectProvider<WtxRsiExecutionBridge> bridgeProvider;
    private WtxRsiStrategyService service;
    private List<Candle> fixtureCandles;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        statePort = new InMemoryWtxRsiPorts.State();
        historyPort = new InMemoryWtxRsiPorts.History();
        candlePort = mock(CandleRepositoryPort.class);
        ws = mock(SimpMessagingTemplate.class);
        properties = new WtxRsiStrategyProperties();
        properties.setInstruments(List.of("MNQ"));
        properties.setTimeframes(List.of("5m"));

        bridge = new RecordingWtxRsiBridge();
        bridgeProvider = (ObjectProvider<WtxRsiExecutionBridge>) mock(ObjectProvider.class);
        when(bridgeProvider.getIfAvailable()).thenReturn(bridge);

        // Default config uses FRACTAL_HH_HL → SmcBiasSource is never consulted,
        // but it must still be injected; an empty stub keeps the construction clean.
        SmcBiasSource smcSource = new SmcBiasSource(new NoopObjectProvider<>());
        WtxRsiBiasResolver biasResolver = new WtxRsiBiasResolver(smcSource);

        service = new WtxRsiStrategyService(
                statePort, historyPort, candlePort, properties, bridgeProvider, ws, biasResolver);

        fixtureCandles = SyntheticCandlesFixture.mnq5m(800, 42);
        stubCandlePort(fixtureCandles);
    }

    private void stubCandlePort(List<Candle> all) {
        // Replay 200-bar windows ending at each closed bar.
        when(candlePort.findRecentCandles(eq(Instrument.MNQ), eq("5m"), anyInt()))
                .thenAnswer(inv -> {
                    int limit = inv.getArgument(2);
                    int upTo = currentReplayBound.get();
                    int from = Math.max(0, upTo - limit + 1);
                    List<Candle> window = new ArrayList<>(all.subList(from, upTo + 1));
                    Collections.reverse(window); // repo returns newest-first
                    return window;
                });
    }

    private final ThreadLocal<Integer> currentReplayBound = ThreadLocal.withInitial(() -> 199);

    private void deliverCandleAt(int barIndex) {
        currentReplayBound.set(barIndex);
        Candle c = fixtureCandles.get(barIndex);
        service.onCandleClosed(new CandleClosed("MNQ", "5m", c.getTimestamp()));
    }

    private void deliverAll() {
        for (int i = 199; i < fixtureCandles.size(); i++) deliverCandleAt(i);
    }

    @Test
    void produces_state_and_history_when_running_through_fixture() {
        deliverAll();
        assertTrue(statePort.load("MNQ", "5m").isPresent(), "state must persist");
        assertFalse(historyPort.store.isEmpty(),
                "noisy-sine fixture must produce at least one signal");
    }

    @Test
    void skips_routing_when_auto_execution_off() {
        // Default state has autoExecutionEnabled = false.
        deliverAll();
        boolean anyOpenOrClose = historyPort.store.stream()
                .anyMatch(r -> r.action() == WtxRsiSignalRecord.Action.OPEN_LONG
                        || r.action() == WtxRsiSignalRecord.Action.OPEN_SHORT
                        || r.action() == WtxRsiSignalRecord.Action.CLOSE_LONG
                        || r.action() == WtxRsiSignalRecord.Action.CLOSE_SHORT);
        assertTrue(anyOpenOrClose, "fixture should fire at least one OPEN/CLOSE in history");
        assertTrue(bridge.opens.isEmpty(),
                "bridge.submitOpen must NOT be called while autoExecutionEnabled=false");
        assertTrue(bridge.closes.isEmpty(),
                "bridge.submitClose must NOT be called while autoExecutionEnabled=false");
        boolean anySkipAuto = historyPort.store.stream()
                .anyMatch(r -> r.routingOutcome() == WtxRoutingOutcome.SKIPPED_AUTO_OFF);
        assertTrue(anySkipAuto, "OPEN/CLOSE records must carry SKIPPED_AUTO_OFF when toggle is off");
    }

    @Test
    void routes_to_bridge_when_auto_execution_on() {
        service.toggleAutoExecution("MNQ", "5m", true);
        deliverAll();
        assertFalse(bridge.opens.isEmpty(),
                "bridge.submitOpen must be invoked at least once with auto-execution ON");
        boolean anyRouted = historyPort.store.stream()
                .anyMatch(r -> r.routingOutcome() == WtxRoutingOutcome.ROUTED);
        assertTrue(anyRouted);
    }

    @Test
    void position_round_trips_flat_to_open_to_flat() {
        service.toggleAutoExecution("MNQ", "5m", true);
        deliverAll();
        WtxRsiStrategyState end = statePort.load("MNQ", "5m").orElseThrow();
        // After 800 noisy bars we expect at least one open trade to have closed
        // (SL hit, TP hit, or reversal). End state can be open or flat — either is OK,
        // but realized PnL must reflect at least one closed trade.
        long closes = historyPort.store.stream()
                .filter(r -> r.action() == WtxRsiSignalRecord.Action.CLOSE_LONG
                        || r.action() == WtxRsiSignalRecord.Action.CLOSE_SHORT)
                .count();
        assertTrue(closes > 0, "expected at least one CLOSE record in fixture");
        if (end.currentPosition() == WtxRsiPosition.FLAT) {
            assertEquals(0, end.entryQty().signum(),
                    "FLAT state must carry zero open qty");
            assertNull(end.stopLoss(), "FLAT state must clear SL");
        }
    }

    @Test
    void toggle_auto_execution_persists_state() {
        WtxRsiStrategyState updated = service.toggleAutoExecution("MNQ", "5m", true);
        assertTrue(updated.autoExecutionEnabled());
        assertTrue(statePort.load("MNQ", "5m").orElseThrow().autoExecutionEnabled());
        service.toggleAutoExecution("MNQ", "5m", false);
        assertFalse(statePort.load("MNQ", "5m").orElseThrow().autoExecutionEnabled());
    }

    @Test
    void skips_unlisted_instruments_and_timeframes() {
        // Different instrument
        service.onCandleClosed(new CandleClosed("MCL", "5m", Instant.now()));
        // Different timeframe
        service.onCandleClosed(new CandleClosed("MNQ", "1h", Instant.now()));
        assertTrue(historyPort.store.isEmpty(), "no history should accumulate for unlisted (instrument, timeframe)");
    }

    @Test
    void toggle_swing_bias_filter_persists_state() {
        WtxRsiStrategyState updated = service.toggleSwingBiasFilter("MNQ", "5m", true);
        assertTrue(updated.swingBiasFilterEnabled());
        assertTrue(statePort.load("MNQ", "5m").orElseThrow().swingBiasFilterEnabled());
        service.toggleSwingBiasFilter("MNQ", "5m", false);
        assertFalse(statePort.load("MNQ", "5m").orElseThrow().swingBiasFilterEnabled());
    }

    @Test
    void swing_bias_is_recorded_on_state_after_each_candle() {
        deliverAll();
        WtxRsiStrategyState end = statePort.load("MNQ", "5m").orElseThrow();
        assertNotNull(end.lastSwingBias(),
                "state should snapshot the last resolved bias for the UI");
    }

    @Test
    void swing_bias_filter_suppresses_contradictory_signals() {
        // Turn the filter on but keep auto-execution off (no broker calls).
        statePort.save(WtxRsiStrategyState.initial("MNQ", "5m").withSwingBiasFilter(true));
        deliverAll();
        boolean anySuppressedByBias = historyPort.store.stream()
                .anyMatch(r -> r.action() == WtxRsiSignalRecord.Action.NONE
                        && r.routingErrorMessage() != null
                        && r.routingErrorMessage().startsWith("swing-bias filter:"));
        assertTrue(anySuppressedByBias,
                "with the filter on, at least one signal in 800 noisy bars must contradict the bias");
    }

    @Test
    void records_skipped_bridge_unavailable_when_provider_returns_null() {
        @SuppressWarnings("unchecked")
        ObjectProvider<WtxRsiExecutionBridge> emptyProvider =
                (ObjectProvider<WtxRsiExecutionBridge>) mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        WtxRsiStrategyService noBridgeService = new WtxRsiStrategyService(
                statePort, historyPort, candlePort, properties, emptyProvider, ws,
                new WtxRsiBiasResolver(new SmcBiasSource(new NoopObjectProvider<>())));
        // Flip toggle ON directly on state so routing is attempted.
        statePort.save(WtxRsiStrategyState.initial("MNQ", "5m").withAutoExecution(true));
        for (int i = 199; i < fixtureCandles.size(); i++) {
            currentReplayBound.set(i);
            Candle c = fixtureCandles.get(i);
            noBridgeService.onCandleClosed(new CandleClosed("MNQ", "5m", c.getTimestamp()));
        }
        boolean anySkipBridge = historyPort.store.stream()
                .anyMatch(r -> r.routingOutcome() == WtxRoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE);
        assertTrue(anySkipBridge,
                "OPEN actions with toggle ON must record SKIPPED_BRIDGE_UNAVAILABLE when no bridge bean");
    }

    @Test
    void chaikin_required_blocks_unconfirmed_entries_but_keeps_exits() {
        // Entry-only gate: with chaikin-required on (chaikin-enabled defaults true),
        // only Chaikin-confirmed signals may OPEN. Exits keep their mechanism.
        properties.setChaikinRequired(true);
        deliverAll();

        // The gate must have fired at least once over 800 noisy bars: blocked
        // entries are logged as NONE with a chaikin-required reason.
        boolean gateFired = historyPort.store.stream().anyMatch(r ->
                r.action() == WtxRsiSignalRecord.Action.NONE
                        && r.routingErrorMessage() != null
                        && r.routingErrorMessage().startsWith("chaikin-required:"));
        assertTrue(gateFired, "expected at least one entry blocked by chaikin-required");

        // Every position actually OPENED must be Chaikin-confirmed.
        boolean anyOpen = historyPort.store.stream().anyMatch(this::isOpen);
        assertTrue(anyOpen, "confirmed signals should still open positions");
        assertTrue(historyPort.store.stream().filter(this::isOpen)
                        .allMatch(WtxRsiSignalRecord::chaikinConfirmed),
                "chaikin-required must only open Chaikin-confirmed entries");

        // Exits (SL / TP / reversal) are unaffected — closes still occur.
        assertTrue(historyPort.store.stream().anyMatch(this::isClose),
                "exits must still fire with the entry gate on");
    }

    private boolean isOpen(WtxRsiSignalRecord r) {
        return r.action() == WtxRsiSignalRecord.Action.OPEN_LONG
                || r.action() == WtxRsiSignalRecord.Action.OPEN_SHORT;
    }

    private boolean isClose(WtxRsiSignalRecord r) {
        return r.action() == WtxRsiSignalRecord.Action.CLOSE_LONG
                || r.action() == WtxRsiSignalRecord.Action.CLOSE_SHORT;
    }

    // ── tiny synthetic candle fixture (duplicated here so the application tests
    //    don't depend on test code from the domain test source set) ────────────
    static final class SyntheticCandlesFixture {
        static List<Candle> mnq5m(int n, long seed) {
            Random rng = new Random(seed);
            List<Candle> out = new ArrayList<>(n);
            Instant ts = Instant.parse("2025-01-02T14:30:00Z");
            double prev = 17000.0;
            for (int i = 0; i < n; i++) {
                double phase = (i / (double) n) * 6.0 * Math.PI;
                double mid = 17000.0 + 80.0 * Math.sin(phase) + (i * 40.0 / n);
                double close = mid + rng.nextGaussian() * 4.0;
                double open = prev;
                double wick = 2.0 + rng.nextDouble() * 6.0;
                double hi = Math.max(open, close) + wick;
                double lo = Math.min(open, close) - wick;
                long volume = 500 + rng.nextInt(4500);
                out.add(new Candle(
                        Instrument.MNQ, "5m",
                        ts.plus(i * 5L, ChronoUnit.MINUTES),
                        BigDecimal.valueOf(round2(open)),
                        BigDecimal.valueOf(round2(hi)),
                        BigDecimal.valueOf(round2(lo)),
                        BigDecimal.valueOf(round2(close)),
                        volume));
                prev = close;
            }
            return out;
        }

        private static double round2(double x) {
            return Math.round(x * 100.0) / 100.0;
        }
    }
}
