package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.engine.indicators.ChaikinIndicator;
import com.riskdesk.domain.engine.indicators.RsiWithSma;
import com.riskdesk.domain.engine.indicators.WaveTrendIndicator;
import com.riskdesk.domain.engine.indicators.WaveTrendIndicator.WaveTrendResult;
import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Causal evaluator that turns indicator series into {@link WtxRsiSignal} objects.
 *
 * <p>Detection rule (LONG; SHORT is symmetric):
 * <ol>
 *   <li>The bar to evaluate emits an RSI bullish cross (RSI crosses its SMA upward).</li>
 *   <li>Within the last {@code syncLookbackBars + 1} bars (inclusive of the current bar),
 *       a WT bullish cross occurred whose <i>zone-mode predicate</i> is satisfied
 *       (depends on {@link WtxRsiZoneMode}).</li>
 * </ol>
 * If both hold, a {@link WtxRsiSignal} is emitted on the RSI-cross bar, with
 * {@code confirmed = sign(Chaikin) == direction} when Chaikin is enabled.
 *
 * <p>This class is stateless. All look-up is bounded by indices into the
 * caller-supplied lists, so it is safe to use both in the live event loop
 * (one bar at a time) and in the backtest engine (full series).
 */
public final class WtxRsiBarEvaluator {

    private WtxRsiBarEvaluator() {}

    public static final class IndicatorSeries {
        public final List<WaveTrendResult> wt;     // size == candles.size() but leading entries may be null
        public final List<RsiWithSma.Sample> rsi;  // padded to candles.size()
        public final BigDecimal[] chaikin;         // candle-aligned, null where not yet computed

        IndicatorSeries(List<WaveTrendResult> wt, List<RsiWithSma.Sample> rsi, BigDecimal[] chaikin) {
            this.wt = wt;
            this.rsi = rsi;
            this.chaikin = chaikin;
        }
    }

    /**
     * Compute candle-aligned WT, RSI/SMA and Chaikin series for the given candles
     * using the parameters from {@code config}.
     */
    public static IndicatorSeries computeIndicators(List<Candle> candles, WtxRsiConfig config) {
        Objects.requireNonNull(candles, "candles");
        Objects.requireNonNull(config, "config");

        int n = candles.size();

        // ── WaveTrend (returns one entry per candle once warmup is complete) ──
        WaveTrendIndicator wtCalc = new WaveTrendIndicator(config.wtN1(), config.wtN2(), config.wtSignalPeriod());
        List<WaveTrendResult> wtRaw = wtCalc.calculate(candles);
        List<WaveTrendResult> wt = new ArrayList<>(n);
        int wtPad = n - wtRaw.size();
        for (int i = 0; i < wtPad; i++) wt.add(null);
        wt.addAll(wtRaw);

        // ── RSI + SMA(RSI) (already candle-aligned) ──────────────────────────
        List<RsiWithSma.Sample> rsi = new RsiWithSma(config.rsiLength(), config.rsiSmaLength()).calculate(candles);

        // ── Chaikin oscillator (candle-aligned with leading nulls) ───────────
        BigDecimal[] chaikin = new BigDecimal[n];
        if (config.chaikinEnabled() && n >= config.chaikinSlow()) {
            ChaikinIndicator ch = new ChaikinIndicator(config.chaikinFast(), config.chaikinSlow(), 20);
            List<BigDecimal> osc = ch.calculateOscillator(candles);
            int pad = n - osc.size();
            for (int j = 0; j < osc.size(); j++) chaikin[pad + j] = osc.get(j);
        }

        return new IndicatorSeries(wt, rsi, chaikin);
    }

    /**
     * Evaluate the candle at {@code barIndex} for a WTX+RSI signal.
     * Returns Optional.empty() when no signal triggers on this exact bar.
     */
    public static Optional<WtxRsiSignal> evaluate(
            List<Candle> candles, IndicatorSeries series, int barIndex, WtxRsiConfig config) {

        if (barIndex < 0 || barIndex >= candles.size()) return Optional.empty();
        RsiWithSma.Sample rsiSample = series.rsi.get(barIndex);
        if (!rsiSample.isReady()) return Optional.empty();
        if (rsiSample.cross() == RsiWithSma.CrossDirection.NONE) return Optional.empty();

        WtxRsiSignal.Side side = (rsiSample.cross() == RsiWithSma.CrossDirection.UP)
                ? WtxRsiSignal.Side.LONG
                : WtxRsiSignal.Side.SHORT;

        // Look back within [barIndex - X, barIndex] for a WT cross of matching direction
        // whose zone-mode predicate holds.
        int earliest = Math.max(0, barIndex - config.syncLookbackBars());
        WaveTrendResult wtAtCross = null;
        int wtCrossBar = -1;
        for (int k = barIndex; k >= earliest; k--) {
            WaveTrendResult wt = series.wt.get(k);
            if (wt == null) continue;
            boolean wantBullish = side == WtxRsiSignal.Side.LONG;
            String requiredCross = wantBullish ? "BULLISH_CROSS" : "BEARISH_CROSS";
            if (!requiredCross.equals(wt.crossover())) continue;
            if (!zoneModePasses(series.wt, k, side, config)) continue;
            wtAtCross = wt;
            wtCrossBar = k;
            break;
        }
        if (wtAtCross == null || wtCrossBar < 0) return Optional.empty();

        BigDecimal chaikinValue = series.chaikin[barIndex];
        boolean confirmed = false;
        if (config.chaikinEnabled() && chaikinValue != null) {
            confirmed = (side == WtxRsiSignal.Side.LONG)
                    ? chaikinValue.signum() > 0
                    : chaikinValue.signum() < 0;
        }

        Candle c = candles.get(barIndex);
        return Optional.of(new WtxRsiSignal(
                barIndex,
                c.getTimestamp(),
                side,
                confirmed,
                wtAtCross.wt1(),
                wtAtCross.wt2(),
                rsiSample.rsi(),
                rsiSample.sma(),
                chaikinValue,
                c.getClose()
        ));
    }

    /** Convenience: emit every signal in the full series. Used by the backtest engine. */
    public static List<WtxRsiSignal> detectAll(List<Candle> candles, WtxRsiConfig config) {
        IndicatorSeries series = computeIndicators(candles, config);
        List<WtxRsiSignal> out = new ArrayList<>();
        for (int i = 0; i < candles.size(); i++) {
            evaluate(candles, series, i, config).ifPresent(out::add);
        }
        return out;
    }

    // ── zone-mode logic ────────────────────────────────────────────────────

    private static boolean zoneModePasses(
            List<WaveTrendResult> wt, int crossBar, WtxRsiSignal.Side side, WtxRsiConfig config) {

        BigDecimal threshold = (side == WtxRsiSignal.Side.LONG) ? config.wtOversold() : config.wtOverbought();
        return switch (config.zoneMode()) {
            case STRICT_ZONE -> inZone(wt.get(crossBar), side, threshold);
            case VISITED_RECENTLY -> {
                int earliest = Math.max(0, crossBar - config.zoneLookbackBars());
                boolean visited = false;
                for (int j = crossBar; j >= earliest; j--) {
                    WaveTrendResult w = wt.get(j);
                    if (w == null) continue;
                    if (inZone(w, side, threshold)) { visited = true; break; }
                }
                yield visited;
            }
            case CROSS_FROM_ZONE -> {
                WaveTrendResult cur = wt.get(crossBar);
                WaveTrendResult prev = (crossBar > 0) ? wt.get(crossBar - 1) : null;
                yield inZone(cur, side, threshold) || inZone(prev, side, threshold);
            }
        };
    }

    private static boolean inZone(WaveTrendResult wt, WtxRsiSignal.Side side, BigDecimal threshold) {
        if (wt == null || wt.wt1() == null) return false;
        return (side == WtxRsiSignal.Side.LONG)
                ? wt.wt1().compareTo(threshold) <= 0
                : wt.wt1().compareTo(threshold) >= 0;
    }
}
