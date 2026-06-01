package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.model.Candle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class WtxRsiBarEvaluatorTest {

    private final WtxRsiConfig defaults = WtxRsiConfig.defaults5m();

    @Test
    void detects_signals_on_both_sides() {
        List<Candle> bars = SyntheticCandles.mnq(500, 42);
        List<WtxRsiSignal> signals = WtxRsiBarEvaluator.detectAll(bars, defaults);
        Set<WtxRsiSignal.Side> sides = signals.stream()
                .map(WtxRsiSignal::side).collect(Collectors.toSet());
        assertFalse(signals.isEmpty(), "synthetic oscillating series must produce signals");
        assertTrue(sides.contains(WtxRsiSignal.Side.LONG));
        assertTrue(sides.contains(WtxRsiSignal.Side.SHORT));
    }

    @Test
    void signals_are_chronologically_ordered() {
        List<Candle> bars = SyntheticCandles.mnq(500, 42);
        List<WtxRsiSignal> signals = WtxRsiBarEvaluator.detectAll(bars, defaults);
        for (int i = 1; i < signals.size(); i++) {
            assertTrue(signals.get(i).barIndex() > signals.get(i - 1).barIndex(),
                    "signals must be monotonically increasing by bar index");
        }
    }

    @Test
    void strict_zone_mode_keeps_long_wt1_in_oversold() {
        List<Candle> bars = SyntheticCandles.mnq(500, 42);
        List<WtxRsiSignal> signals = WtxRsiBarEvaluator.detectAll(bars, defaults);
        for (WtxRsiSignal s : signals) {
            if (s.side() == WtxRsiSignal.Side.LONG) {
                // In STRICT_ZONE mode the WT1 captured on the WT-cross bar must be
                // in the OS band. We can't directly read the WT-cross bar from the
                // signal record, but the signal exposes the WT values that were
                // present at the WT-cross bar (not the emit bar).
                assertTrue(s.wt1().compareTo(defaults.wtOversold()) <= 0,
                        "LONG signal in STRICT mode must show WT1 in oversold zone, got " + s.wt1());
            } else {
                assertTrue(s.wt1().compareTo(defaults.wtOverbought()) >= 0,
                        "SHORT signal in STRICT mode must show WT1 in overbought zone, got " + s.wt1());
            }
        }
    }

    @Test
    void visited_recently_mode_finds_more_signals_than_strict() {
        WtxRsiConfig strict = defaults;
        WtxRsiConfig visited = new WtxRsiConfig(
                strict.wtN1(), strict.wtN2(), strict.wtSignalPeriod(),
                strict.wtOverbought(), strict.wtOversold(),
                strict.rsiLength(), strict.rsiSmaLength(),
                strict.syncLookbackBars(),
                WtxRsiZoneMode.VISITED_RECENTLY, 8,
                strict.fractalLeftRight(), strict.fractalMaxLookback(),
                strict.swingBufferTicks(), strict.tickSize(), strict.tickValueUsd(),
                strict.baseContracts(),
                strict.tpMode(), strict.tpRMultiple(),
                strict.chaikinFast(), strict.chaikinSlow(), strict.chaikinEnabled(),
                strict.biasSource()
        );
        List<Candle> bars = SyntheticCandles.mnq(500, 42);
        int strictCount = WtxRsiBarEvaluator.detectAll(bars, strict).size();
        int visitedCount = WtxRsiBarEvaluator.detectAll(bars, visited).size();
        assertTrue(visitedCount >= strictCount,
                "VISITED_RECENTLY should be a superset of STRICT_ZONE (got "
                        + visitedCount + " vs " + strictCount + ")");
    }

    @Test
    void confirmed_flag_matches_chaikin_sign() {
        List<Candle> bars = SyntheticCandles.mnq(500, 42);
        List<WtxRsiSignal> signals = WtxRsiBarEvaluator.detectAll(bars, defaults);
        for (WtxRsiSignal s : signals) {
            if (!s.confirmed()) continue;
            assertNotNull(s.chaikin(), "confirmed signal must have a chaikin value");
            if (s.side() == WtxRsiSignal.Side.LONG) {
                assertTrue(s.chaikin().signum() > 0, "LONG confirmation requires Chaikin > 0");
            } else {
                assertTrue(s.chaikin().signum() < 0, "SHORT confirmation requires Chaikin < 0");
            }
        }
    }

    @Test
    void sync_lookback_zero_requires_same_bar() {
        WtxRsiConfig tight = new WtxRsiConfig(
                defaults.wtN1(), defaults.wtN2(), defaults.wtSignalPeriod(),
                defaults.wtOverbought(), defaults.wtOversold(),
                defaults.rsiLength(), defaults.rsiSmaLength(),
                0,
                defaults.zoneMode(), defaults.zoneLookbackBars(),
                defaults.fractalLeftRight(), defaults.fractalMaxLookback(),
                defaults.swingBufferTicks(), defaults.tickSize(), defaults.tickValueUsd(),
                defaults.baseContracts(),
                defaults.tpMode(), defaults.tpRMultiple(),
                defaults.chaikinFast(), defaults.chaikinSlow(), defaults.chaikinEnabled(),
                defaults.biasSource()
        );
        List<Candle> bars = SyntheticCandles.mnq(500, 42);
        int tightCount = WtxRsiBarEvaluator.detectAll(bars, tight).size();
        int defaultCount = WtxRsiBarEvaluator.detectAll(bars, defaults).size();
        assertTrue(tightCount <= defaultCount,
                "narrower sync window must produce ≤ signals than wider window");
    }
}
