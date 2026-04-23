package com.riskdesk.domain.engine.strategy.model;

import java.math.BigDecimal;

/**
 * Price position relative to the Value Area (POC / VAH / VAL).
 *
 * <p>INSIDE_VA spans the entire Value Area; AT_POC is a narrower band around the POC
 * (within a tolerance caller-defined, typically 0.05 × Value Area width).
 */
public enum PriceLocation {
    ABOVE_VAH,
    INSIDE_VA,
    AT_POC,
    BELOW_VAL,
    UNKNOWN;

    /**
     * Compute location from price + POC/VAH/VAL. Returns UNKNOWN when any of the
     * inputs is null — callers must treat UNKNOWN as "no data", not "neutral".
     *
     * @param pocTolerancePct fraction of the VA width that counts as "at POC"
     *                        (e.g. 0.05 = 5%). Values ≤ 0 disable the AT_POC bucket.
     */
    public static PriceLocation of(BigDecimal price, Double poc, Double vah, Double val,
                                   double pocTolerancePct) {
        if (price == null || poc == null || vah == null || val == null) return UNKNOWN;
        double p = price.doubleValue();
        if (p > vah) return ABOVE_VAH;
        if (p < val) return BELOW_VAL;
        if (pocTolerancePct > 0.0) {
            double vaWidth = Math.max(1e-9, vah - val);
            double band = vaWidth * pocTolerancePct;
            if (Math.abs(p - poc) <= band) return AT_POC;
        }
        return INSIDE_VA;
    }
}
