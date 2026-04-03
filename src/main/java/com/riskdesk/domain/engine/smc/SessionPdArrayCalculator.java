package com.riskdesk.domain.engine.smc;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates Premium/Discount/Equilibrium zones based on a price range.
 * Used for both session-range (intraday) and structural (swing) PD arrays.
 *
 * <ul>
 *   <li>PREMIUM: price above the equilibrium band (upper half of range)</li>
 *   <li>DISCOUNT: price below the equilibrium band (lower half of range)</li>
 *   <li>EQUILIBRIUM: price within the equilibrium band (around 50% of range)</li>
 * </ul>
 */
public class SessionPdArrayCalculator {

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
     * @param rangeHigh the high of the range (session high or swing high)
     * @param rangeLow  the low of the range (session low or swing low)
     * @param currentPrice the current price
     * @return the PD array result, or null if the range is zero/invalid
     */
    public PdArrayResult compute(BigDecimal rangeHigh, BigDecimal rangeLow, BigDecimal currentPrice) {
        if (rangeHigh == null || rangeLow == null || currentPrice == null) {
            return null;
        }
        BigDecimal range = rangeHigh.subtract(rangeLow);
        if (range.compareTo(BigDecimal.ZERO) <= 0) {
            return null; // flat or inverted range
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

        return new PdArrayResult(zone, rangeHigh, rangeLow, equilibrium, eqTop, eqBottom);
    }

    /**
     * Result of a PD Array zone calculation.
     */
    public record PdArrayResult(
        String zone,              // PREMIUM, DISCOUNT, or EQUILIBRIUM
        BigDecimal rangeHigh,
        BigDecimal rangeLow,
        BigDecimal equilibrium,   // 50% of range
        BigDecimal premiumStart,  // top of equilibrium band (above = premium)
        BigDecimal discountEnd    // bottom of equilibrium band (below = discount)
    ) {}
}
