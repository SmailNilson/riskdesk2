package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WtxRsiRiskCalculatorTest {

    private final WtxRsiConfig defaults = WtxRsiConfig.defaults5m();

    @Test
    void builds_long_plan_with_sl_below_swing_low() {
        List<Candle> bars = SyntheticCandles.mnq(500, 42);
        List<WtxRsiSignal> signals = WtxRsiBarEvaluator.detectAll(bars, defaults);
        WtxRsiRiskPlan p = firstPlan(signals, bars, WtxRsiSignal.Side.LONG, defaults);
        assertTrue(p.stopLoss().compareTo(p.entryPrice()) < 0, "LONG SL must be below entry");
        assertTrue(p.swingReference().compareTo(p.stopLoss()) >= 0,
                "swing reference must sit at or above the rounded SL");
    }

    @Test
    void builds_short_plan_with_sl_above_swing_high() {
        List<Candle> bars = SyntheticCandles.mnq(500, 42);
        List<WtxRsiSignal> signals = WtxRsiBarEvaluator.detectAll(bars, defaults);
        WtxRsiRiskPlan p = firstPlan(signals, bars, WtxRsiSignal.Side.SHORT, defaults);
        assertTrue(p.stopLoss().compareTo(p.entryPrice()) > 0, "SHORT SL must be above entry");
    }

    private static WtxRsiRiskPlan firstPlan(
            List<WtxRsiSignal> signals, List<Candle> bars,
            WtxRsiSignal.Side side, WtxRsiConfig cfg) {
        for (WtxRsiSignal s : signals) {
            if (s.side() != side) continue;
            BigDecimal entry = bars.get(s.barIndex()).getClose();
            Optional<WtxRsiRiskPlan> plan = WtxRsiRiskCalculator.build(s, bars, entry, cfg);
            if (plan.isPresent()) return plan.get();
        }
        return fail("expected at least one " + side + " signal with a valid fractal-based plan");
    }

    @Test
    void chaikin_confirmation_does_not_change_contract_count() {
        List<Candle> bars = SyntheticCandles.mnq(500, 42);
        List<WtxRsiSignal> signals = WtxRsiBarEvaluator.detectAll(bars, defaults);
        WtxRsiRiskPlan confirmed = findFirstPlanWithConfirmation(signals, bars, true);
        WtxRsiRiskPlan unconfirmed = findFirstPlanWithConfirmation(signals, bars, false);
        if (confirmed == null || unconfirmed == null) return; // fixture-dependent
        // Chaikin no longer scales the position — confirmed and unconfirmed
        // entries both size at base-contracts. Confirmation is an entry gate only.
        assertEquals(defaults.baseContracts(), confirmed.contracts());
        assertEquals(defaults.baseContracts(), unconfirmed.contracts());
    }

    private WtxRsiRiskPlan findFirstPlanWithConfirmation(
            List<WtxRsiSignal> signals, List<Candle> bars, boolean confirmed) {
        for (WtxRsiSignal s : signals) {
            if (s.confirmed() != confirmed) continue;
            Optional<WtxRsiRiskPlan> p = WtxRsiRiskCalculator.build(
                    s, bars, bars.get(s.barIndex()).getClose(), defaults);
            if (p.isPresent()) return p.get();
        }
        return null;
    }

    @Test
    void r_multiple_tp_respects_initial_risk() {
        WtxRsiConfig withTp = new WtxRsiConfig(
                defaults.wtN1(), defaults.wtN2(), defaults.wtSignalPeriod(),
                defaults.wtOverbought(), defaults.wtOversold(),
                defaults.rsiLength(), defaults.rsiSmaLength(),
                defaults.syncLookbackBars(),
                defaults.zoneMode(), defaults.zoneLookbackBars(),
                defaults.fractalLeftRight(), defaults.fractalMaxLookback(),
                defaults.swingBufferTicks(), defaults.tickSize(), defaults.tickValueUsd(),
                defaults.baseContracts(),
                WtxRsiTpMode.R_MULTIPLE, new BigDecimal("2.0"),
                defaults.chaikinFast(), defaults.chaikinSlow(), defaults.chaikinEnabled(),
                defaults.biasSource()
        );
        List<Candle> bars = SyntheticCandles.mnq(500, 42);
        List<WtxRsiSignal> signals = WtxRsiBarEvaluator.detectAll(bars, withTp);
        WtxRsiRiskPlan plan = null;
        WtxRsiSignal pickedSignal = null;
        for (WtxRsiSignal s : signals) {
            Optional<WtxRsiRiskPlan> p = WtxRsiRiskCalculator.build(
                    s, bars, bars.get(s.barIndex()).getClose(), withTp);
            if (p.isPresent()) { plan = p.get(); pickedSignal = s; break; }
        }
        assertNotNull(plan, "expected at least one buildable plan");
        assertNotNull(plan.takeProfit());
        BigDecimal expected = (pickedSignal.side() == WtxRsiSignal.Side.LONG)
                ? plan.entryPrice().add(plan.initialRiskPerContract().multiply(new BigDecimal("2.0")))
                : plan.entryPrice().subtract(plan.initialRiskPerContract().multiply(new BigDecimal("2.0")));
        // Tolerance: one tick due to rounding
        assertTrue(plan.takeProfit().subtract(expected).abs().compareTo(withTp.tickSize()) <= 0,
                "TP must land within one tick of entry ± R·risk, got " + plan.takeProfit() + " vs " + expected);
    }
}
