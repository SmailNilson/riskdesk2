package com.riskdesk.domain.engine.indicators;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Detects the current market regime based on EMA alignment and Bollinger Band width.
 *
 * <ul>
 *   <li>TRENDING_UP: EMA9 > EMA50 > EMA200 and BB expanding</li>
 *   <li>TRENDING_DOWN: EMA9 < EMA50 < EMA200 and BB expanding</li>
 *   <li>RANGING: EMAs close together and BB contracting</li>
 *   <li>CHOPPY: EMAs crossed without clear direction</li>
 * </ul>
 *
 * <h2>Momentum fast-path</h2>
 * The legacy EMA + BB path is structurally lagging — both indicators trail real
 * price action by several candles, which kept the detector reporting CHOPPY
 * during fast directional breakouts (e.g. MCL 2026-04-30: -152¢ in 58 minutes
 * stayed CHOPPY because EMA9/50/200 hadn't realigned and BB expansion was still
 * ramping).
 *
 * <p>The {@link #detect(BigDecimal, BigDecimal, BigDecimal, boolean, List, BigDecimal)}
 * overload adds a momentum-based fast-path: when the close-to-close move over the
 * last {@code FAST_PATH_LOOKBACK} candles exceeds {@code FAST_PATH_THRESHOLD * ATR * sqrt(N)}
 * (i.e. ~1.8 sigma above the random-walk noise envelope), the regime is
 * classified as {@code TRENDING_UP} / {@code TRENDING_DOWN} immediately,
 * regardless of EMA alignment. Falls through to the existing logic when
 * insufficient data or when the move is within noise.
 */
public class MarketRegimeDetector {

    public static final String TRENDING_UP = "TRENDING_UP";
    public static final String TRENDING_DOWN = "TRENDING_DOWN";
    public static final String RANGING = "RANGING";
    public static final String CHOPPY = "CHOPPY";

    /** If |ema9 - ema50| / ema50 < this threshold, EMAs are considered "close". */
    private static final BigDecimal EMA_PROXIMITY_THRESHOLD = new BigDecimal("0.002"); // 0.2%

    /** Fast-path: how many candles back to compute the momentum move. 6 candles ≈ 30 min on 5m. */
    private static final int FAST_PATH_LOOKBACK = 6;

    /**
     * Fast-path noise multiplier. A random walk over N candles has expected absolute
     * displacement ~ ATR * sqrt(N). A 1.8x multiplier corresponds to roughly the
     * 93rd percentile of pure-noise moves — anything beyond is very unlikely to be
     * a chop and very likely to be a directional cassure.
     */
    private static final double FAST_PATH_THRESHOLD = 1.8;

    /**
     * Detect the current market regime.
     *
     * @param ema9   EMA 9 value (fast)
     * @param ema50  EMA 50 value (medium)
     * @param ema200 EMA 200 value (slow)
     * @param bbExpanding true if Bollinger Band trend is expanding
     * @return the detected regime string
     */
    public String detect(BigDecimal ema9, BigDecimal ema50, BigDecimal ema200, boolean bbExpanding) {
        if (ema9 == null || ema50 == null || ema200 == null) {
            return CHOPPY;
        }

        boolean bullishAlignment = ema9.compareTo(ema50) > 0 && ema50.compareTo(ema200) > 0;
        boolean bearishAlignment = ema9.compareTo(ema50) < 0 && ema50.compareTo(ema200) < 0;
        boolean emasClose = isClose(ema9, ema50);

        if (bullishAlignment && bbExpanding) {
            return TRENDING_UP;
        }
        if (bearishAlignment && bbExpanding) {
            return TRENDING_DOWN;
        }
        if (emasClose && !bbExpanding) {
            return RANGING;
        }
        return CHOPPY;
    }

    /**
     * Detect the market regime with a momentum-based fast-path that catches real
     * directional cassures the lagging EMA/BB logic misses.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Compute the close-to-close move over the last {@value #FAST_PATH_LOOKBACK} candles.</li>
     *   <li>Compute the random-walk envelope {@code expectedNoise = ATR * sqrt(N)}.</li>
     *   <li>If {@code |move| > FAST_PATH_THRESHOLD * expectedNoise} → return {@code TRENDING_UP}
     *       / {@code TRENDING_DOWN} based on the move sign — bypass the EMA/BB logic entirely.</li>
     *   <li>Otherwise fall through to {@link #detect(BigDecimal, BigDecimal, BigDecimal, boolean)}.</li>
     * </ol>
     *
     * <p>Backward compat: when {@code recentCloses} is null/short or {@code atr} is
     * null/zero, this overload behaves identically to the legacy 4-arg overload.
     * The fast-path can ONLY add a TRENDING classification — it never converts a
     * legacy TRENDING/RANGING result into something else.
     *
     * @param ema9          EMA 9 value (fast)
     * @param ema50         EMA 50 value (medium)
     * @param ema200        EMA 200 value (slow)
     * @param bbExpanding   true if Bollinger Band trend is expanding
     * @param recentCloses  recent close prices in chronological order (oldest first, newest last);
     *                      requires at least {@value #FAST_PATH_LOOKBACK} entries to engage
     * @param atr           ATR over the same timeframe as {@code recentCloses}
     * @return the detected regime string
     */
    public String detect(BigDecimal ema9, BigDecimal ema50, BigDecimal ema200, boolean bbExpanding,
                         List<BigDecimal> recentCloses, BigDecimal atr) {
        int direction = fastPathDirection(recentCloses, atr);
        if (direction > 0) return TRENDING_UP;
        if (direction < 0) return TRENDING_DOWN;
        return detect(ema9, ema50, ema200, bbExpanding);
    }

    /**
     * Returns the momentum fast-path direction: +1 (bullish breakout), -1 (bearish breakout),
     * or 0 (no fast-path signal — insufficient data or move within noise envelope).
     *
     * <p>Exposed publicly so call sites that need the directional hint without
     * collapsing through the regime string (e.g. {@code RegimeContextAgent}) can
     * use the same threshold logic.
     *
     * @param recentCloses recent close prices in chronological order (oldest first)
     * @param atr          ATR over the same timeframe
     * @return -1, 0, or +1
     */
    public int fastPathDirection(List<BigDecimal> recentCloses, BigDecimal atr) {
        if (recentCloses == null || recentCloses.size() < FAST_PATH_LOOKBACK
            || atr == null || atr.signum() <= 0) {
            return 0;
        }
        int n = recentCloses.size();
        BigDecimal current = recentCloses.get(n - 1);
        BigDecimal past = recentCloses.get(n - FAST_PATH_LOOKBACK);
        if (current == null || past == null) return 0;
        double move = current.subtract(past).doubleValue();
        double noise = atr.doubleValue() * Math.sqrt(FAST_PATH_LOOKBACK);
        if (Math.abs(move) > FAST_PATH_THRESHOLD * noise) {
            return move > 0 ? +1 : -1;
        }
        return 0;
    }

    /**
     * Count how many consecutive candles (from most recent) share the same regime.
     * Useful for "time in regime" computation.
     *
     * @param ema9Values   list of EMA9 values, oldest first
     * @param ema50Values  list of EMA50 values, oldest first
     * @param ema200Values list of EMA200 values, oldest first
     * @param bbExpandingValues list of BB expanding booleans, oldest first
     * @return number of consecutive candles from the end with the same regime
     */
    public int durationCandles(List<BigDecimal> ema9Values,
                               List<BigDecimal> ema50Values,
                               List<BigDecimal> ema200Values,
                               List<Boolean> bbExpandingValues) {
        if (ema9Values == null || ema9Values.isEmpty()) return 0;

        int size = Math.min(Math.min(ema9Values.size(), ema50Values.size()),
                           Math.min(ema200Values.size(), bbExpandingValues.size()));
        if (size == 0) return 0;

        String currentRegime = detect(
            ema9Values.get(size - 1), ema50Values.get(size - 1),
            ema200Values.get(size - 1), bbExpandingValues.get(size - 1));

        int count = 1;
        for (int i = size - 2; i >= 0; i--) {
            String regime = detect(ema9Values.get(i), ema50Values.get(i),
                                   ema200Values.get(i), bbExpandingValues.get(i));
            if (!regime.equals(currentRegime)) break;
            count++;
        }
        return count;
    }

    /**
     * Check if two timeframes are aligned (same directional regime).
     */
    public boolean htfAligned(String focusRegime, String htfRegime) {
        if (focusRegime == null || htfRegime == null) return false;
        // Both trending in the same direction = aligned
        if (TRENDING_UP.equals(focusRegime) && TRENDING_UP.equals(htfRegime)) return true;
        if (TRENDING_DOWN.equals(focusRegime) && TRENDING_DOWN.equals(htfRegime)) return true;
        return false;
    }

    private boolean isClose(BigDecimal a, BigDecimal b) {
        if (b.compareTo(BigDecimal.ZERO) == 0) return false;
        BigDecimal diff = a.subtract(b).abs();
        BigDecimal ratio = diff.divide(b.abs(), 6, RoundingMode.HALF_UP);
        return ratio.compareTo(EMA_PROXIMITY_THRESHOLD) < 0;
    }
}
