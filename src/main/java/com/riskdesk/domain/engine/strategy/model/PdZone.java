package com.riskdesk.domain.engine.strategy.model;

/**
 * ICT/SMC Premium-Discount zoning for the current session range.
 *
 * <p>Independent from the Volume Profile {@link PriceLocation} because the
 * anchoring is different: PD uses the session high/low midpoint, VA uses the
 * distribution of traded volume.
 */
public enum PdZone {
    PREMIUM,
    EQUILIBRIUM,
    DISCOUNT,
    UNKNOWN;

    public static PdZone fromLabel(String label) {
        if (label == null) return UNKNOWN;
        return switch (label.toUpperCase()) {
            case "PREMIUM"     -> PREMIUM;
            case "DISCOUNT"    -> DISCOUNT;
            case "EQUILIBRIUM" -> EQUILIBRIUM;
            default            -> UNKNOWN;
        };
    }
}
