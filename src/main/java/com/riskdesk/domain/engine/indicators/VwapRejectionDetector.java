package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * Detects a bearish VWAP rejection on a 5-minute MNQ candle.
 *
 * <h2>Rejection Condition</h2>
 * A candle constitutes a bearish VWAP rejection when:
 * <ol>
 *   <li><b>Touch</b>: the candle's high reached or exceeded
 *       {@code vwap × (1 - touchTolerancePct)} — i.e. price tagged the VWAP level.</li>
 *   <li><b>Close</b>: the candle's close is strictly below the VWAP — sellers won the
 *       candle after testing VWAP, confirming bearish intent.</li>
 * </ol>
 *
 * <h2>Rationale</h2>
 * In a risk-off environment where MNQ is under distribution, institutional sellers
 * systematically defend the VWAP level. A candle that probes VWAP but closes below it
 * signals that the large sell programs are re-loaded and the downtrend is resuming.
 * This is the follower confirmation needed after the MCL breakout in the ONIMS strategy.
 *
 * <h2>Domain Purity</h2>
 * No Spring annotations, no persistence, no I/O.
 */
public class VwapRejectionDetector {

    /**
     * Default touch tolerance: the candle high must reach within 0.1% of VWAP to count as a touch.
     * Expressed as a fraction (0.001 = 0.1%).
     */
    public static final double DEFAULT_TOUCH_TOLERANCE = 0.001;

    private final BigDecimal touchTolerancePct;

    public VwapRejectionDetector() {
        this(DEFAULT_TOUCH_TOLERANCE);
    }

    public VwapRejectionDetector(double touchTolerancePct) {
        if (touchTolerancePct < 0 || touchTolerancePct > 0.05) {
            throw new IllegalArgumentException("touchTolerancePct must be in [0, 0.05]");
        }
        this.touchTolerancePct = BigDecimal.valueOf(touchTolerancePct);
    }

    /**
     * Result returned when a bearish VWAP rejection is detected.
     *
     * @param candleClose     the close price of the rejection candle
     * @param vwap            the VWAP level at the time of the candle
     * @param candleHigh      the high of the rejection candle
     * @param distanceBelowPct percentage distance of close below VWAP (positive = close < vwap)
     */
    public record RejectionResult(
            BigDecimal candleClose,
            BigDecimal vwap,
            BigDecimal candleHigh,
            double distanceBelowPct
    ) {}

    /**
     * Determines whether the given candle represents a bearish VWAP rejection.
     *
     * @param candle the most recently closed 5m candle for MNQ
     * @param vwap   the VWAP value computed for MNQ at this candle's timestamp
     * @return {@code true} if the candle touched VWAP and closed below it
     */
    public boolean isRejection(Candle candle, BigDecimal vwap) {
        Objects.requireNonNull(candle, "candle must not be null");
        Objects.requireNonNull(vwap,   "vwap must not be null");

        if (vwap.compareTo(BigDecimal.ZERO) <= 0) return false;

        // Touch condition: high >= vwap * (1 - tolerance)
        BigDecimal touchThreshold = vwap.multiply(BigDecimal.ONE.subtract(touchTolerancePct))
                                        .setScale(5, RoundingMode.HALF_UP);
        boolean touched = candle.getHigh().compareTo(touchThreshold) >= 0;

        // Close condition: close < vwap (sellers won the candle)
        boolean closedBelow = candle.getClose().compareTo(vwap) < 0;

        return touched && closedBelow;
    }

    /**
     * Provides a detailed {@link RejectionResult} when a rejection is detected, or
     * {@link Optional#empty()} if the condition is not met.
     *
     * @param candle the most recently closed 5m candle for MNQ
     * @param vwap   the VWAP value for MNQ at this candle's timestamp
     * @return a populated result or empty if no rejection
     */
    public Optional<RejectionResult> detect(Candle candle, BigDecimal vwap) {
        if (!isRejection(candle, vwap)) {
            return Optional.empty();
        }

        // Distance of close below VWAP, expressed as percentage (positive = below)
        BigDecimal rawDistance = vwap.subtract(candle.getClose());
        double distancePct = rawDistance.divide(vwap, 8, RoundingMode.HALF_UP)
                                        .multiply(BigDecimal.valueOf(100))
                                        .doubleValue();

        return Optional.of(new RejectionResult(
                candle.getClose(),
                vwap,
                candle.getHigh(),
                distancePct
        ));
    }
}
