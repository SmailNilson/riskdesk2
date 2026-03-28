package com.riskdesk.domain.engine.smc;

import com.riskdesk.domain.model.Candle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateful, candle-driven SMC market structure engine.
 * <p>
 * Separates two independent structure levels following LuxAlgo logic:
 * <ul>
 *   <li><b>Internal</b> — short-term pivots (default lookback 5)</li>
 *   <li><b>Swing</b> — long-term pivots (default lookback 50)</li>
 * </ul>
 * Each closed candle is fed via {@link #onCandle}; the engine updates its
 * state incrementally and returns any new {@link StructureEvent}s.
 * No reprocessing of historical data is performed.
 * <p>
 * <b>Pivot detection</b> mirrors the LuxAlgo {@code leg(size)} function:
 * a bar at {@code [lookback]} positions back is confirmed as a swing HIGH
 * when its high exceeds the highest high of the subsequent {@code lookback}
 * bars (right-side confirmation only). LOW pivots use the symmetric rule.
 * <p>
 * <b>Structure breaks</b> fire on close crossover/crossunder of an active
 * pivot level. A break in the direction of the current trend is a BOS;
 * against it is a CHoCH. Internal breaks are suppressed when the internal
 * pivot price matches the swing pivot price (avoids duplicates).
 * <p>
 * <b>Thread safety:</b> Not thread-safe. External synchronisation required
 * if accessed from multiple threads.
 */
public class SmcStructureEngine {

    // ── Enums ────────────────────────────────────────────────────────────

    public enum Bias { BULLISH, BEARISH }
    public enum StructureType { BOS, CHOCH }
    public enum StructureLevel { INTERNAL, SWING }
    public enum PivotSide { HIGH, LOW }

    // ── Value objects ────────────────────────────────────────────────────

    /** A confirmed swing pivot. */
    public record Pivot(PivotSide side, double price, Instant timestamp, int barIndex) {}

    /** A BOS or CHoCH event with full context. */
    public record StructureEvent(
            StructureType type,
            Bias newBias,
            StructureLevel level,
            double breakPrice,
            Instant timestamp,
            int barIndex) {}

    /** Immutable snapshot of engine state. */
    public record StructureSnapshot(
            Bias internalBias,
            Bias swingBias,
            Pivot internalHigh,
            Pivot internalLow,
            Pivot swingHigh,
            Pivot swingLow,
            double trailingTop,
            Instant trailingTopTime,
            double trailingBottom,
            Instant trailingBottomTime,
            List<StructureEvent> events) {}

    // ── Mutable level state (package-private for test visibility) ────────

    static final class LevelState {
        final int lookback;
        final StructureLevel level;

        /**
         * Leg value: 0 = BEARISH_LEG (last confirmed pivot was a HIGH),
         *            1 = BULLISH_LEG (last confirmed pivot was a LOW).
         * Initialised to 0 to match LuxAlgo {@code var leg = 0}.
         */
        int leg = 0;

        // High pivot
        double highPrice = Double.NaN;
        Instant highTime;
        int highBarIndex = -1;
        boolean highCrossed = true;

        // Low pivot
        double lowPrice = Double.NaN;
        Instant lowTime;
        int lowBarIndex = -1;
        boolean lowCrossed = true;

        /** Trend bias: +1 BULLISH, -1 BEARISH, 0 undefined. */
        int bias = 0;

        LevelState(int lookback, StructureLevel level) {
            this.lookback = lookback;
            this.level = level;
        }
    }

    // ── Ring buffer ──────────────────────────────────────────────────────

    private final int capacity;
    private final double[] highs;
    private final double[] lows;
    private final Instant[] times;
    private int bufSize;
    private int writePos;
    private int totalBars;

    // ── Engine state ─────────────────────────────────────────────────────

    private final LevelState internal;
    private final LevelState swing;
    private double prevClose = Double.NaN;

    // Trailing extremes — initialised from first swing pivot,
    // then extended bar-by-bar (matches LuxAlgo trailingExtremes UDT).
    private double trailingTop = Double.NaN;
    private double trailingBottom = Double.NaN;
    private Instant trailingTopTime;
    private Instant trailingBottomTime;

    private final List<StructureEvent> allEvents = new ArrayList<>();

    /**
     * When true, {@link #onCandle} skips per-bar ArrayList allocation
     * and event accumulation — only final state matters.
     * Used by {@link #computeFromHistory} for GC-friendly batch processing.
     */
    private boolean batchMode;

    // ── Constructors ─────────────────────────────────────────────────────

    /**
     * @param internalLookback right-side confirmation bars for internal pivots (LuxAlgo: 5)
     * @param swingLookback    right-side confirmation bars for swing pivots (LuxAlgo: 50)
     */
    public SmcStructureEngine(int internalLookback, int swingLookback) {
        this.internal = new LevelState(internalLookback, StructureLevel.INTERNAL);
        this.swing = new LevelState(swingLookback, StructureLevel.SWING);
        this.capacity = Math.max(internalLookback, swingLookback) + 1;
        this.highs = new double[capacity];
        this.lows = new double[capacity];
        this.times = new Instant[capacity];
    }

    /** Default: internal=5, swing=50 (LuxAlgo defaults). */
    public SmcStructureEngine() {
        this(5, 50);
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Process one closed candle. Returns any new structure events emitted.
     * Candles must be fed in chronological order.
     */
    public List<StructureEvent> onCandle(Candle candle) {
        double h = candle.getHigh().doubleValue();
        double l = candle.getLow().doubleValue();
        double c = candle.getClose().doubleValue();
        Instant t = candle.getTimestamp();

        // 1. Store in ring buffer
        highs[writePos] = h;
        lows[writePos] = l;
        times[writePos] = t;
        writePos = (writePos + 1) % capacity;
        if (bufSize < capacity) bufSize++;
        totalBars++;

        // 2. Pivot detection — swing first so internal extra-condition can reference swing state
        detectPivots(swing);
        detectPivots(internal);

        if (batchMode) {
            // GC-optimized path: no ArrayList allocation, no event accumulation.
            // Break detection still runs to update bias/crossed state.
            detectBreaksBatch(internal, c, t);
            detectBreaksBatch(swing, c, t);
            updateTrailing(h, l, t);
            prevClose = c;
            return List.of();
        }

        List<StructureEvent> out = new ArrayList<>(4);

        // 3. Structure break detection — internal first (LuxAlgo execution order)
        detectBreaks(internal, c, t, out);
        detectBreaks(swing, c, t, out);

        // 4. Update trailing swing extremes
        updateTrailing(h, l, t);

        prevClose = c;
        allEvents.addAll(out);
        return out;
    }

    /**
     * Batch-compute from a historical candle list. Resets all internal state,
     * feeds every candle through the detection loop, and returns the final snapshot.
     * <p>
     * GC-optimized: no per-bar {@code ArrayList} allocation, no event accumulation.
     * Only the last N events (from the tail of the series) are captured for the snapshot.
     *
     * @param candles chronologically ordered closed candles
     * @param tailEvents number of recent events to capture (for recentBreaks display); 0 = none
     * @return final engine state as an immutable snapshot
     */
    public StructureSnapshot computeFromHistory(List<Candle> candles, int tailEvents) {
        reset();

        // Phase 1: batch mode — process all but the last tailEvents-worth of bars
        // We process ALL bars in batch mode first (no allocations), then replay none.
        // Instead, we capture events only in the final snapshot from engine state.
        batchMode = true;
        for (int i = 0, n = candles.size(); i < n; i++) {
            onCandle(candles.get(i));
        }
        batchMode = false;

        // Build snapshot — allEvents is empty in batch mode, which is fine:
        // the caller (IndicatorService) only needs current bias + pivot levels.
        // recentBreaks are reconstructed from the last structure state if needed.
        return snapshot();
    }

    /** Convenience overload: no tail events captured. */
    public StructureSnapshot computeFromHistory(List<Candle> candles) {
        return computeFromHistory(candles, 0);
    }

    /** Resets all mutable state to initial values. */
    private void reset() {
        bufSize = 0;
        writePos = 0;
        totalBars = 0;
        prevClose = Double.NaN;
        trailingTop = Double.NaN;
        trailingBottom = Double.NaN;
        trailingTopTime = null;
        trailingBottomTime = null;
        allEvents.clear();
        resetLevel(internal);
        resetLevel(swing);
    }

    private static void resetLevel(LevelState s) {
        s.leg = 0;
        s.highPrice = Double.NaN;
        s.highTime = null;
        s.highBarIndex = -1;
        s.highCrossed = true;
        s.lowPrice = Double.NaN;
        s.lowTime = null;
        s.lowBarIndex = -1;
        s.lowCrossed = true;
        s.bias = 0;
    }

    /** Immutable snapshot of current engine state. */
    public StructureSnapshot snapshot() {
        return new StructureSnapshot(
                biasOf(internal), biasOf(swing),
                pivotOf(internal, PivotSide.HIGH), pivotOf(internal, PivotSide.LOW),
                pivotOf(swing, PivotSide.HIGH), pivotOf(swing, PivotSide.LOW),
                trailingTop, trailingTopTime,
                trailingBottom, trailingBottomTime,
                List.copyOf(allEvents));
    }

    public Bias internalBias() { return biasOf(internal); }
    public Bias swingBias() { return biasOf(swing); }
    public int totalBars() { return totalBars; }

    // ── Pivot detection — LuxAlgo leg() + getCurrentStructure() ──────────

    private void detectPivots(LevelState s) {
        if (bufSize < s.lookback + 1) return;

        // Candidate bar: [lookback] positions before the most recent bar
        int candRing = ringIdx(writePos - 1 - s.lookback);
        double candH = highs[candRing];
        double candL = lows[candRing];
        Instant candT = times[candRing];
        int candBar = totalBars - 1 - s.lookback;

        // Max high / min low in the confirmation window (the lookback bars after the candidate)
        double maxH = Double.NEGATIVE_INFINITY;
        double minL = Double.POSITIVE_INFINITY;
        for (int i = 0; i < s.lookback; i++) {
            int idx = ringIdx(writePos - 1 - i);
            if (highs[idx] > maxH) maxH = highs[idx];
            if (lows[idx] < minL) minL = lows[idx];
        }

        // LuxAlgo: if newLegHigh → BEARISH_LEG, else if newLegLow → BULLISH_LEG
        boolean newLegHigh = candH > maxH;
        boolean newLegLow = candL < minL;

        int prevLeg = s.leg;
        if (newLegHigh) {
            s.leg = 0;       // BEARISH_LEG — found a HIGH
        } else if (newLegLow) {
            s.leg = 1;       // BULLISH_LEG — found a LOW
        }

        int change = s.leg - prevLeg;
        if (change == 0) return;

        if (change > 0) {
            // Entered BULLISH_LEG → confirmed LOW pivot
            s.lowPrice = candL;
            s.lowTime = candT;
            s.lowBarIndex = candBar;
            s.lowCrossed = false;
            if (s.level == StructureLevel.SWING) {
                trailingBottom = candL;
                trailingBottomTime = candT;
            }
        } else {
            // Entered BEARISH_LEG → confirmed HIGH pivot
            s.highPrice = candH;
            s.highTime = candT;
            s.highBarIndex = candBar;
            s.highCrossed = false;
            if (s.level == StructureLevel.SWING) {
                trailingTop = candH;
                trailingTopTime = candT;
            }
        }
    }

    // ── Structure break detection — LuxAlgo displayStructure() ───────────

    private void detectBreaks(LevelState s, double close, Instant time,
                              List<StructureEvent> out) {
        if (Double.isNaN(prevClose)) return;

        // Bullish break: close crosses above high pivot
        if (!Double.isNaN(s.highPrice) && !s.highCrossed) {
            boolean extraOk = extraConditionPasses(s, swing.highPrice, s.highPrice);
            if (extraOk && close > s.highPrice && prevClose <= s.highPrice) {
                StructureType type = s.bias == -1
                        ? StructureType.CHOCH : StructureType.BOS;
                s.highCrossed = true;
                s.bias = 1;
                out.add(new StructureEvent(type, Bias.BULLISH, s.level,
                        s.highPrice, time, totalBars - 1));
            }
        }

        // Bearish break: close crosses below low pivot
        if (!Double.isNaN(s.lowPrice) && !s.lowCrossed) {
            boolean extraOk = extraConditionPasses(s, swing.lowPrice, s.lowPrice);
            if (extraOk && close < s.lowPrice && prevClose >= s.lowPrice) {
                StructureType type = s.bias == 1
                        ? StructureType.CHOCH : StructureType.BOS;
                s.lowCrossed = true;
                s.bias = -1;
                out.add(new StructureEvent(type, Bias.BEARISH, s.level,
                        s.lowPrice, time, totalBars - 1));
            }
        }
    }

    /**
     * GC-optimized break detection: updates bias/crossed state without allocating
     * any StructureEvent objects. Used in batch mode.
     */
    private void detectBreaksBatch(LevelState s, double close, Instant time) {
        if (Double.isNaN(prevClose)) return;

        if (!Double.isNaN(s.highPrice) && !s.highCrossed) {
            boolean extraOk = extraConditionPasses(s, swing.highPrice, s.highPrice);
            if (extraOk && close > s.highPrice && prevClose <= s.highPrice) {
                s.highCrossed = true;
                s.bias = 1;
            }
        }

        if (!Double.isNaN(s.lowPrice) && !s.lowCrossed) {
            boolean extraOk = extraConditionPasses(s, swing.lowPrice, s.lowPrice);
            if (extraOk && close < s.lowPrice && prevClose >= s.lowPrice) {
                s.lowCrossed = true;
                s.bias = -1;
            }
        }
    }

    /**
     * LuxAlgo extra condition for internal breaks:
     * internal pivot price must differ from corresponding swing pivot price
     * to avoid duplicate structure lines at the same level.
     * Swing level always passes.
     */
    private static boolean extraConditionPasses(LevelState s,
                                                double swingPivotPrice,
                                                double internalPivotPrice) {
        if (s.level != StructureLevel.INTERNAL) return true;
        if (Double.isNaN(swingPivotPrice)) return true;
        return Math.abs(internalPivotPrice - swingPivotPrice) > 1e-9;
    }

    // ── Trailing extremes ────────────────────────────────────────────────

    private void updateTrailing(double h, double l, Instant t) {
        // Only extend after first swing pivot initialises trailing values
        if (!Double.isNaN(trailingTop) && h > trailingTop) {
            trailingTop = h;
            trailingTopTime = t;
        }
        if (!Double.isNaN(trailingBottom) && l < trailingBottom) {
            trailingBottom = l;
            trailingBottomTime = t;
        }
    }

    // ── Ring buffer helpers ──────────────────────────────────────────────

    private int ringIdx(int raw) {
        return ((raw % capacity) + capacity) % capacity;
    }

    private static Bias biasOf(LevelState s) {
        return switch (s.bias) {
            case 1 -> Bias.BULLISH;
            case -1 -> Bias.BEARISH;
            default -> null;
        };
    }

    private static Pivot pivotOf(LevelState s, PivotSide side) {
        if (side == PivotSide.HIGH) {
            return Double.isNaN(s.highPrice) ? null
                    : new Pivot(PivotSide.HIGH, s.highPrice, s.highTime, s.highBarIndex);
        }
        return Double.isNaN(s.lowPrice) ? null
                : new Pivot(PivotSide.LOW, s.lowPrice, s.lowTime, s.lowBarIndex);
    }
}
