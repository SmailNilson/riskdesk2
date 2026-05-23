package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives a {@link WtxRsiSwingBias} from the candles by looking at the two
 * most-recent confirmed Williams fractals on each side:
 *
 * <ul>
 *   <li>Higher Highs <i>and</i> Higher Lows → {@link WtxRsiSwingBias#BULLISH}</li>
 *   <li>Lower Highs <i>and</i> Lower Lows → {@link WtxRsiSwingBias#BEARISH}</li>
 *   <li>Mixed (e.g. HH + LL) or not enough fractals → {@link WtxRsiSwingBias#NEUTRAL}</li>
 * </ul>
 *
 * Purely-domain, no Spring. Uses {@link WilliamsFractal} so the same {@code Y}
 * pivot definition the SL relies on is also what defines the bias.
 *
 * <p>Why this shape (vs an SMC-engine bias):
 * <ul>
 *   <li>No extra dependency — the strategy already computes fractals for SL.</li>
 *   <li>Identical Y in both code paths means "what defines a swing" is one decision.</li>
 *   <li>Cheap: O(maxLookback) per evaluation, called once per closed bar.</li>
 * </ul>
 */
public final class WtxRsiSwingBiasDetector {

    private WtxRsiSwingBiasDetector() {}

    /**
     * @param candles          full series up to and including the bar being evaluated
     * @param leftRight        Williams pivot leftRight (same as {@code WtxRsiConfig.fractalLeftRight()})
     * @param maxLookback      how far back to search for the second-most-recent pivot
     *                         on each side (in bars)
     * @return resolved bias for the latest closed bar; NEUTRAL if either side
     *         has fewer than 2 confirmed pivots in the window.
     */
    public static WtxRsiSwingBias detect(List<Candle> candles, int leftRight, int maxLookback) {
        if (candles == null || candles.size() < leftRight * 2 + 2) return WtxRsiSwingBias.NEUTRAL;

        int asOfIndex = candles.size() - 1;
        List<WilliamsFractal.Fractal> highs = findRecent(candles, asOfIndex, leftRight, maxLookback, WilliamsFractal.Fractal.Kind.HIGH);
        List<WilliamsFractal.Fractal> lows = findRecent(candles, asOfIndex, leftRight, maxLookback, WilliamsFractal.Fractal.Kind.LOW);
        if (highs.size() < 2 || lows.size() < 2) return WtxRsiSwingBias.NEUTRAL;

        // findRecent returns newest-first; highs[0] = last confirmed pivot, highs[1] = previous.
        boolean higherHigh = highs.get(0).price().compareTo(highs.get(1).price()) > 0;
        boolean higherLow = lows.get(0).price().compareTo(lows.get(1).price()) > 0;
        boolean lowerHigh = highs.get(0).price().compareTo(highs.get(1).price()) < 0;
        boolean lowerLow = lows.get(0).price().compareTo(lows.get(1).price()) < 0;

        if (higherHigh && higherLow) return WtxRsiSwingBias.BULLISH;
        if (lowerHigh && lowerLow) return WtxRsiSwingBias.BEARISH;
        return WtxRsiSwingBias.NEUTRAL;
    }

    private static List<WilliamsFractal.Fractal> findRecent(
            List<Candle> candles, int asOfIndex, int leftRight, int maxLookback,
            WilliamsFractal.Fractal.Kind kind) {

        List<WilliamsFractal.Fractal> out = new ArrayList<>(2);
        int latestPivot = asOfIndex - leftRight;
        int earliest = Math.max(leftRight, latestPivot - maxLookback + 1);
        for (int i = latestPivot; i >= earliest && out.size() < 2; i--) {
            if (isFractal(candles, i, leftRight, kind)) {
                Candle c = candles.get(i);
                BigDecimal price = (kind == WilliamsFractal.Fractal.Kind.LOW) ? c.getLow() : c.getHigh();
                out.add(new WilliamsFractal.Fractal(i, c.getTimestamp(), price, kind));
            }
        }
        return out;
    }

    private static boolean isFractal(List<Candle> candles, int i, int leftRight,
                                     WilliamsFractal.Fractal.Kind kind) {
        BigDecimal pivot = (kind == WilliamsFractal.Fractal.Kind.LOW)
                ? candles.get(i).getLow() : candles.get(i).getHigh();
        for (int j = i - leftRight; j <= i + leftRight; j++) {
            if (j == i || j < 0 || j >= candles.size()) continue;
            BigDecimal other = (kind == WilliamsFractal.Fractal.Kind.LOW)
                    ? candles.get(j).getLow() : candles.get(j).getHigh();
            if (kind == WilliamsFractal.Fractal.Kind.LOW) {
                if (other.compareTo(pivot) <= 0) return false;
            } else {
                if (other.compareTo(pivot) >= 0) return false;
            }
        }
        return true;
    }
}
