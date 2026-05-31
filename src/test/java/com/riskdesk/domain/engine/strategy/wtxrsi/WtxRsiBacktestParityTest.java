package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity guard for U3 — proves the backtest engine is a faithful interpreter of
 * the shared {@link WtxRsiTransition#reduce} FSM rather than a second
 * implementation of the trading logic.
 *
 * <p>It drives an independent "reference" reduce loop over the exact same
 * candles + signals + state seeding the engine uses, and asserts the resulting
 * trade sequences are identical. Because both paths now funnel through
 * {@code reduce}, the live orchestrator and the backtest can no longer diverge:
 * any future change to the FSM moves both at once.
 */
class WtxRsiBacktestParityTest {

    private final WtxRsiConfig config = WtxRsiConfig.defaults5m();

    @Test
    void engine_trades_match_a_reference_reduce_loop_and_fill_at_signal_close() {
        List<Candle> candles = SyntheticCandles.mnq(800, 42);

        // ── Engine path ──
        WtxRsiBacktestEngine engine = new WtxRsiBacktestEngine(config);
        List<WtxRsiTrade> engineTrades = engine.run(candles).trades();
        assertFalse(engineTrades.isEmpty(), "fixture must produce trades");

        // ── Reference path: a hand-rolled reduce loop (mirrors the live service) ──
        WtxRsiBarEvaluator.IndicatorSeries series = WtxRsiBarEvaluator.computeIndicators(candles, config);
        Map<Integer, WtxRsiSignal> signalsByBar = new HashMap<>();
        for (int i = 0; i < candles.size(); i++) {
            WtxRsiBarEvaluator.evaluate(candles, series, i, config)
                    .ifPresent(s -> signalsByBar.put(s.barIndex(), s));
        }

        WtxRsiStrategyState state = WtxRsiStrategyState.initial("BACKTEST", "", config.chaikinRequired());
        List<TradeKey> reference = new ArrayList<>();
        WtxRsiSignal openSignal = null;
        WtxRsiRiskPlan openPlan = null;

        for (int i = 0; i < candles.size(); i++) {
            Candle bar = candles.get(i);
            Optional<WtxRsiSignal> sig = Optional.ofNullable(signalsByBar.get(i));
            WtxRsiTransition.Result r = WtxRsiTransition.reduce(
                    state, bar, candles, sig, WtxRsiSwingBias.NEUTRAL, config);

            for (WtxRsiDecision d : r.decisions()) {
                if (d instanceof WtxRsiDecision.Open o) {
                    openSignal = o.signal();
                    openPlan = o.plan();
                    // Fill model: entry is the rounded close of the signal bar.
                    assertEquals(0,
                            openPlan.entryPrice().compareTo(
                                    WtxRsiRiskCalculator.roundToTick(o.signal().close(), config.tickSize())),
                            "entry must fill at the rounded signal-bar close, not the next bar open");
                } else if (d instanceof WtxRsiDecision.Close c && openPlan != null) {
                    reference.add(new TradeKey(openSignal.side(), openPlan.entryPrice(), c.exitPrice()));
                    openSignal = null;
                    openPlan = null;
                }
            }
            state = r.newState();
        }
        if (state.currentPosition() != WtxRsiPosition.FLAT && openPlan != null) {
            BigDecimal lastClose = candles.get(candles.size() - 1).getClose();
            reference.add(new TradeKey(openSignal.side(), openPlan.entryPrice(), lastClose));
        }

        // ── The engine's trades must equal the reference sequence exactly ──
        List<TradeKey> engineKeys = engineTrades.stream()
                .map(t -> new TradeKey(t.side(), t.entryPrice(), t.exitPrice()))
                .toList();
        assertEquals(reference, engineKeys,
                "backtest engine must reproduce the reference reduce loop trade-for-trade");
    }

    /** Equality key for a trade — side + entry + exit fully identify it for parity. */
    private record TradeKey(WtxRsiSignal.Side side, BigDecimal entry, BigDecimal exit) {
        @Override public boolean equals(Object o) {
            if (!(o instanceof TradeKey k)) return false;
            return side == k.side
                    && entry.compareTo(k.entry) == 0
                    && exit.compareTo(k.exit) == 0;
        }
        @Override public int hashCode() { return side.hashCode(); }
    }
}
