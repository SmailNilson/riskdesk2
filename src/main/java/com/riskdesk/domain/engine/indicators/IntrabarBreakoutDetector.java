package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Detects an N-period channel breakout on 5-minute candles with volume confirmation.
 *
 * <h2>Breakout Condition</h2>
 * <ol>
 *   <li><b>Price</b>: the current candle's close is strictly above the highest close
 *       among the previous {@code lookbackPeriods} candles (the "resistance level").</li>
 *   <li><b>Volume</b>: the current candle's volume is greater than
 *       {@code volumeMultiplier × average volume} of the lookback window.</li>
 * </ol>
 *
 * <h2>Rationale</h2>
 * Requiring elevated volume prevents false breakouts on thin bars, a common issue
 * in commodity futures (MCL) during low-liquidity periods or immediately after OPEC+
 * announcements when the order book is thin before the spike re-attracts liquidity.
 *
 * <h2>Domain Purity</h2>
 * No Spring annotations, no persistence, no I/O.
 * Designed to be called from the application layer with freshly loaded candles.
 */
public class IntrabarBreakoutDetector {

    /** Default lookback: last 10 completed 5m candles = 50 minutes of context. */
    public static final int DEFAULT_LOOKBACK = 10;

    /** Default volume multiplier: current volume must exceed 1.5× the rolling average. */
    public static final double DEFAULT_VOLUME_MULTIPLIER = 1.5;

    /**
     * Result returned when a breakout is detected.
     *
     * @param breakoutClose     the close price of the breakout candle
     * @param resistanceLevel   the N-period high close that was exceeded
     * @param currentVolume     volume of the breakout candle
     * @param averageVolume     rolling average volume used for the comparison
     * @param volumeRatio       currentVolume / averageVolume
     */
    public record BreakoutResult(
            BigDecimal breakoutClose,
            BigDecimal resistanceLevel,
            long currentVolume,
            long averageVolume,
            double volumeRatio
    ) {}

    private final int    lookbackPeriods;
    private final double volumeMultiplier;

    public IntrabarBreakoutDetector() {
        this(DEFAULT_LOOKBACK, DEFAULT_VOLUME_MULTIPLIER);
    }

    public IntrabarBreakoutDetector(int lookbackPeriods, double volumeMultiplier) {
        if (lookbackPeriods < 2) throw new IllegalArgumentException("lookbackPeriods must be >= 2");
        if (volumeMultiplier <= 0) throw new IllegalArgumentException("volumeMultiplier must be > 0");
        this.lookbackPeriods   = lookbackPeriods;
        this.volumeMultiplier  = volumeMultiplier;
    }

    /**
     * Analyses a list of 5m candles (oldest first) and returns a {@link BreakoutResult}
     * if the most recent candle constitutes a confirmed breakout.
     *
     * <p>Requires at least {@code lookbackPeriods + 1} candles (lookback window + current candle).
     * Returns {@link Optional#empty()} if the list is too short or no breakout is detected.
     *
     * @param candles ordered oldest-first; last element is the most recent closed candle
     */
    public Optional<BreakoutResult> detect(List<Candle> candles) {
        Objects.requireNonNull(candles, "candles must not be null");
        int minRequired = lookbackPeriods + 1;
        if (candles.size() < minRequired) {
            return Optional.empty();
        }

        // The current (most recent) candle is the last in the list
        Candle current  = candles.get(candles.size() - 1);
        // The lookback window is the N candles immediately preceding the current one
        List<Candle> window = candles.subList(candles.size() - 1 - lookbackPeriods,
                                              candles.size() - 1);

        // --- Price condition: current close > highest close in the lookback window ---
        BigDecimal resistanceLevel = window.stream()
                .map(Candle::getClose)
                .max(BigDecimal::compareTo)
                .orElseThrow();

        if (current.getClose().compareTo(resistanceLevel) <= 0) {
            return Optional.empty();
        }

        // --- Volume condition: current volume > multiplier × average window volume ---
        long totalVolume    = window.stream().mapToLong(Candle::getVolume).sum();
        double avgVolumeExact = (double) totalVolume / window.size();

        if (avgVolumeExact < 1.0) {
            // Cannot evaluate volume ratio without meaningful reference — skip
            return Optional.empty();
        }

        long averageVolume = Math.round(avgVolumeExact);
        double volumeRatio = (double) current.getVolume() / avgVolumeExact;
        if (volumeRatio < volumeMultiplier) {
            return Optional.empty();
        }

        return Optional.of(new BreakoutResult(
                current.getClose(),
                resistanceLevel,
                current.getVolume(),
                averageVolume,
                BigDecimal.valueOf(volumeRatio).setScale(2, RoundingMode.HALF_UP).doubleValue()
        ));
    }
}
