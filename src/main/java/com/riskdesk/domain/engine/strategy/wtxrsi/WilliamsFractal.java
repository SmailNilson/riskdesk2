package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Williams fractal pivot detector.
 *
 * A bar at index {@code i} is:
 *   - a <b>fractal low</b>  iff its low is strictly the lowest among
 *     {@code [i - leftRight, i + leftRight]}
 *   - a <b>fractal high</b> iff its high is strictly the highest in the same window.
 *
 * A fractal is only <i>confirmed</i> once {@code leftRight} bars to its right
 * have closed. The strategy's stop-loss must reference confirmed fractals only,
 * otherwise SL would shift after entry (look-ahead). Use
 * {@link #findMostRecentConfirmedLow(List, int, int)} /
 * {@link #findMostRecentConfirmedHigh(List, int, int)} which both honour this rule.
 *
 * Ties: a bar that equals its neighbours is <b>not</b> a fractal (strict inequality).
 */
public final class WilliamsFractal {

    private WilliamsFractal() {}

    public record Fractal(int barIndex, Instant timestamp, BigDecimal price, Kind kind) {
        public enum Kind { LOW, HIGH }
    }

    /**
     * Returns the most recent <b>confirmed</b> fractal low whose pivot index is
     * within the last {@code maxLookback} candles (counting back from the most
     * recent fully-closed candle). Pivots are confirmed only when at least
     * {@code leftRight} bars exist to their right.
     *
     * @return Optional fractal — empty if none is found in the lookback range.
     */
    public static Optional<Fractal> findMostRecentConfirmedLow(
            List<Candle> candles, int leftRight, int maxLookback) {
        return findMostRecent(candles, leftRight, maxLookback, Fractal.Kind.LOW);
    }

    public static Optional<Fractal> findMostRecentConfirmedHigh(
            List<Candle> candles, int leftRight, int maxLookback) {
        return findMostRecent(candles, leftRight, maxLookback, Fractal.Kind.HIGH);
    }

    /**
     * Variant useful for the backtest engine: only consider candles whose index
     * is at or before {@code asOfIndex} (inclusive). Confirmation still requires
     * {@code leftRight} bars to the right of the pivot, all of which must also
     * be at or before {@code asOfIndex}. The pivot is therefore at most
     * {@code asOfIndex - leftRight}.
     */
    public static Optional<Fractal> findMostRecentConfirmedLow(
            List<Candle> candles, int asOfIndex, int leftRight, int maxLookback) {
        return findMostRecentBounded(candles, asOfIndex, leftRight, maxLookback, Fractal.Kind.LOW);
    }

    public static Optional<Fractal> findMostRecentConfirmedHigh(
            List<Candle> candles, int asOfIndex, int leftRight, int maxLookback) {
        return findMostRecentBounded(candles, asOfIndex, leftRight, maxLookback, Fractal.Kind.HIGH);
    }

    // ── private ────────────────────────────────────────────────────────────

    private static Optional<Fractal> findMostRecent(
            List<Candle> candles, int leftRight, int maxLookback, Fractal.Kind kind) {
        Objects.requireNonNull(candles, "candles");
        if (candles.isEmpty()) return Optional.empty();
        return findMostRecentBounded(candles, candles.size() - 1, leftRight, maxLookback, kind);
    }

    private static Optional<Fractal> findMostRecentBounded(
            List<Candle> candles, int asOfIndex, int leftRight, int maxLookback, Fractal.Kind kind) {
        Objects.requireNonNull(candles, "candles");
        if (leftRight <= 0) throw new IllegalArgumentException("leftRight must be > 0");
        if (asOfIndex < 0 || asOfIndex >= candles.size()) return Optional.empty();

        int latestPivot = asOfIndex - leftRight;
        if (latestPivot < leftRight) return Optional.empty();

        int earliestPivot = Math.max(leftRight, latestPivot - maxLookback + 1);

        for (int i = latestPivot; i >= earliestPivot; i--) {
            if (isFractal(candles, i, leftRight, kind)) {
                Candle c = candles.get(i);
                BigDecimal price = (kind == Fractal.Kind.LOW) ? c.getLow() : c.getHigh();
                return Optional.of(new Fractal(i, c.getTimestamp(), price, kind));
            }
        }
        return Optional.empty();
    }

    private static boolean isFractal(List<Candle> candles, int i, int leftRight, Fractal.Kind kind) {
        BigDecimal pivot = (kind == Fractal.Kind.LOW)
                ? candles.get(i).getLow()
                : candles.get(i).getHigh();
        for (int j = i - leftRight; j <= i + leftRight; j++) {
            if (j == i || j < 0 || j >= candles.size()) continue;
            BigDecimal other = (kind == Fractal.Kind.LOW)
                    ? candles.get(j).getLow()
                    : candles.get(j).getHigh();
            if (kind == Fractal.Kind.LOW) {
                if (other.compareTo(pivot) <= 0) return false;
            } else {
                if (other.compareTo(pivot) >= 0) return false;
            }
        }
        return true;
    }
}
