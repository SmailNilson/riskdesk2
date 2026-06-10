package com.riskdesk.domain.engine.strategy.wtx;

import com.riskdesk.domain.engine.indicators.WaveTrendIndicator.WaveTrendResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zone-only entry behaviour (the top-train-Z35 preset shape): with {@code useCompra1/useVenta1}
 * disabled and {@code nsc/nsv = ±35}, a WaveTrend cross fires ONLY when it happens in the
 * overbought/oversold zone — a mid-range cross emits no signal (transition-gated, not steady-state).
 */
class WtxBarEvaluatorZoneEntryTest {

    // 10:00 ET — outside the NY force-close window, session filter disabled in defaults().
    private static final Instant TS = Instant.parse("2026-03-05T15:00:00Z");

    private static WaveTrendResult wt(double wt1, double wt2) {
        return new WaveTrendResult(BigDecimal.valueOf(wt1), BigDecimal.valueOf(wt2),
                BigDecimal.valueOf(wt1 - wt2), null, "NEUTRAL");
    }

    private static WtxConfig zoneCfg() {
        return WtxConfig.defaults()
                .withIndicatorParams(5, 14, 2)
                .withSignalZone(BigDecimal.valueOf(35), BigDecimal.valueOf(-35), false, false);
    }

    private static WtxStrategyState flat() {
        return WtxStrategyState.initial("MNQ", "10m", BigDecimal.valueOf(10_000));
    }

    @Test
    void bullishCross_insideOversoldZone_opensLong() {
        Optional<WtxSignal> sig = WtxBarEvaluator.evaluate(
                wt(-40, -38), wt(-36, -37), zoneCfg(), flat(), TS, "10m");
        assertTrue(sig.isPresent());
        assertEquals(WtxSignalType.COMPRA, sig.get().signalType());
        assertEquals(WtxAction.OPEN_LONG, sig.get().suggestedAction());
    }

    @Test
    void bullishCross_outsideZone_isSilent() {
        // Crossover happens but wt1 (-30) is above nsv (-35) and compra1 is disabled → no signal at all.
        Optional<WtxSignal> sig = WtxBarEvaluator.evaluate(
                wt(-32, -31), wt(-30, -30.5), zoneCfg(), flat(), TS, "10m");
        assertTrue(sig.isEmpty());
    }

    @Test
    void bearishCross_insideOverboughtZone_opensShort() {
        Optional<WtxSignal> sig = WtxBarEvaluator.evaluate(
                wt(40, 38), wt(36, 36.5), zoneCfg(), flat(), TS, "10m");
        assertTrue(sig.isPresent());
        assertEquals(WtxSignalType.VENTA, sig.get().signalType());
        assertEquals(WtxAction.OPEN_SHORT, sig.get().suggestedAction());
    }

    @Test
    void midRangeCross_firesAgain_whenEveryCrossModeIsEnabled() {
        // Sanity contrast: same mid-range cross DOES fire (as COMPRA_1) once compra1/venta1 are on.
        WtxConfig everyCross = WtxConfig.defaults()
                .withIndicatorParams(5, 14, 2)
                .withSignalZone(BigDecimal.valueOf(35), BigDecimal.valueOf(-35), true, true);
        Optional<WtxSignal> sig = WtxBarEvaluator.evaluate(
                wt(-32, -31), wt(-30, -30.5), everyCross, flat(), TS, "10m");
        assertTrue(sig.isPresent());
        assertEquals(WtxSignalType.COMPRA_1, sig.get().signalType());
    }
}
