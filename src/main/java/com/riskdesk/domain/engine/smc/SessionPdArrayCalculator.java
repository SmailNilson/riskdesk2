package com.riskdesk.domain.engine.smc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Calculates Premium/Discount/Equilibrium zones based on a price range.
 * Used for both session-range (intraday) and structural (swing) PD arrays.
 *
 * <ul>
 *   <li>PREMIUM: price above the equilibrium band (upper half of range)</li>
 *   <li>DISCOUNT: price below the equilibrium band (lower half of range)</li>
 *   <li>EQUILIBRIUM: price within the equilibrium band (around 50% of range)</li>
 *   <li>UNDEFINED: range is flat, inverted, or otherwise not yet computable —
 *       <b>never returned as null.</b> The result carries a human-readable
 *       {@code reason} so operators can audit why the zone could not be
 *       determined (e.g. "session just started, only one print received").</li>
 * </ul>
 *
 * <p><b>PR-12 · No silent null.</b> Two failure modes used to both collapse
 * into {@code null}, making it impossible to distinguish a programming bug
 * (someone passed a null in) from a benign data-state edge case (the session
 * range has not formed yet). The calculator now:
 * <ul>
 *   <li>Throws {@link NullPointerException} on null inputs — that was always
 *       a caller bug and should fail fast rather than bubble up as a silent
 *       empty zone.</li>
 *   <li>Returns a sentinel {@link PdArrayResult} with {@code zone = "UNDEFINED"}
 *       and an explanatory {@code reason} when the range is flat or inverted.
 *       A WARN is logged so operators see the anomaly without needing to
 *       grep for null downstream.</li>
 * </ul>
 */
public class SessionPdArrayCalculator {

    private static final Logger log = LoggerFactory.getLogger(SessionPdArrayCalculator.class);

    /** Zone label returned when the range is degenerate and PD cannot be computed. */
    public static final String UNDEFINED_ZONE = "UNDEFINED";

    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal DEFAULT_EQUILIBRIUM_BAND_PCT = new BigDecimal("0.05"); // 5% each side

    private final BigDecimal equilibriumBandPct;

    public SessionPdArrayCalculator() {
        this(DEFAULT_EQUILIBRIUM_BAND_PCT);
    }

    /**
     * @param equilibriumBandPct percentage of range on each side of the 50% midpoint
     *                           that defines the equilibrium band (e.g., 0.05 = 5%)
     */
    public SessionPdArrayCalculator(BigDecimal equilibriumBandPct) {
        if (equilibriumBandPct == null || equilibriumBandPct.compareTo(BigDecimal.ZERO) < 0
                || equilibriumBandPct.compareTo(new BigDecimal("0.5")) > 0) {
            throw new IllegalArgumentException(
                "equilibriumBandPct must be between 0 and 0.5, got: " + equilibriumBandPct);
        }
        this.equilibriumBandPct = equilibriumBandPct;
    }

    /**
     * Compute the PD array zone for a given price within a range.
     *
     * @param rangeHigh    high of the range (session high or swing high); must be non-null
     * @param rangeLow     low of the range (session low or swing low); must be non-null
     * @param currentPrice current price; must be non-null
     * @return a {@link PdArrayResult} — never {@code null}. On degenerate range
     *         (flat or inverted) the result carries {@link #UNDEFINED_ZONE} and
     *         a {@code reason} describing why.
     * @throws NullPointerException if any argument is {@code null}
     */
    public PdArrayResult compute(BigDecimal rangeHigh, BigDecimal rangeLow, BigDecimal currentPrice) {
        Objects.requireNonNull(rangeHigh, "rangeHigh must not be null");
        Objects.requireNonNull(rangeLow, "rangeLow must not be null");
        Objects.requireNonNull(currentPrice, "currentPrice must not be null");

        BigDecimal range = rangeHigh.subtract(rangeLow);
        int sign = range.compareTo(BigDecimal.ZERO);
        if (sign == 0) {
            String reason = "flat range: rangeHigh == rangeLow (" + rangeHigh + ")";
            log.warn("PdArray compute -> UNDEFINED: {}", reason);
            return PdArrayResult.undefined(rangeHigh, rangeLow, reason);
        }
        if (sign < 0) {
            String reason = "inverted range: rangeHigh=" + rangeHigh + " < rangeLow=" + rangeLow;
            log.warn("PdArray compute -> UNDEFINED: {}", reason);
            return PdArrayResult.undefined(rangeHigh, rangeLow, reason);
        }

        BigDecimal equilibrium = rangeLow.add(range.divide(TWO, 10, RoundingMode.HALF_UP));
        BigDecimal bandSize = range.multiply(equilibriumBandPct);
        BigDecimal eqTop = equilibrium.add(bandSize);
        BigDecimal eqBottom = equilibrium.subtract(bandSize);

        String zone;
        if (currentPrice.compareTo(eqTop) > 0) {
            zone = "PREMIUM";
        } else if (currentPrice.compareTo(eqBottom) < 0) {
            zone = "DISCOUNT";
        } else {
            zone = "EQUILIBRIUM";
        }

        return new PdArrayResult(zone, rangeHigh, rangeLow, equilibrium, eqTop, eqBottom, null);
    }

    /**
     * Result of a PD Array zone calculation.
     *
     * <p>When {@code zone} equals {@link #UNDEFINED_ZONE}, {@code equilibrium},
     * {@code premiumStart}, and {@code discountEnd} are {@code null} and
     * {@code reason} carries an operator-facing explanation. For any real zone
     * (PREMIUM / DISCOUNT / EQUILIBRIUM) {@code reason} is {@code null}.
     */
    public record PdArrayResult(
        String zone,              // PREMIUM, DISCOUNT, EQUILIBRIUM, or UNDEFINED
        BigDecimal rangeHigh,
        BigDecimal rangeLow,
        BigDecimal equilibrium,   // 50% of range (null when UNDEFINED)
        BigDecimal premiumStart,  // top of equilibrium band (null when UNDEFINED)
        BigDecimal discountEnd,   // bottom of equilibrium band (null when UNDEFINED)
        String reason             // non-null only when zone == UNDEFINED
    ) {
        /** Factory for the sentinel "can't compute" result — preserves inputs for audit. */
        public static PdArrayResult undefined(BigDecimal rangeHigh, BigDecimal rangeLow, String reason) {
            return new PdArrayResult(UNDEFINED_ZONE, rangeHigh, rangeLow, null, null, null, reason);
        }

        /** Convenience: was the zone successfully computed? */
        public boolean isDefined() {
            return !UNDEFINED_ZONE.equals(zone);
        }
    }
}
