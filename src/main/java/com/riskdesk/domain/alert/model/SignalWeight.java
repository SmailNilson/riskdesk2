package com.riskdesk.domain.alert.model;

import java.util.Locale;
import java.util.Set;

/**
 * Confluence weight, family, and structural priority for each qualified signal type.
 *
 * <p>Non-cumul rule: within families {@code Momentum} and {@code Flow},
 * only the maximum weight counts (EMA + MACD = 1.0, not 2.0).
 * All other families cumulate normally (CHoCH 2.0 + BOS 1.5 = 3.5).
 */
public enum SignalWeight {

    ORDER_BLOCK(3.0f, "Structure",       1),
    CHOCH      (2.0f, "SMC",             2),
    WAVETREND  (2.0f, "Oscillateur",     3),
    BOS        (1.5f, "SMC",             4),
    EQUAL_LEVEL(1.0f, "Liquidite",       5),
    FVG        (1.0f, "Structure_FVG",   6),
    SUPERTREND (1.0f, "Tendance",        7),
    VWAP_CROSS (1.0f, "Niveaux",         8),
    EMA        (1.0f, "Momentum",        9),
    MACD       (1.0f, "Momentum",       10),
    CHAIKIN    (1.0f, "Flow",           11),
    DELTA_FLOW (1.0f, "Flow",           12),
    RSI        (0.5f, "Oscillateur_RSI", 13);

    private static final Set<String> NON_CUMUL_FAMILIES = Set.of("Momentum", "Flow");

    private final float weight;
    private final String family;
    private final int priority;

    SignalWeight(float weight, String family, int priority) {
        this.weight = weight;
        this.family = family;
        this.priority = priority;
    }

    public float weight()   { return weight; }
    public String family()  { return family; }
    public int priority()   { return priority; }

    public static boolean isNonCumulFamily(String family) {
        return NON_CUMUL_FAMILIES.contains(family);
    }

    /**
     * Maps an {@link Alert} to its confluence weight.
     * Returns {@code null} for non-eligible alerts (RISK, BOLLINGER, MTF_LEVEL, behaviour categories, etc.).
     */
    public static SignalWeight fromAlert(Alert alert) {
        if (alert == null || alert.category() == null) return null;
        String msg = alert.message() == null ? "" : alert.message().toUpperCase(Locale.ROOT);

        return switch (alert.category()) {
            case ORDER_BLOCK, ORDER_BLOCK_VWAP -> {
                if (msg.contains("MITIGATED") || msg.contains("INVALIDATED"))
                    yield SignalWeight.ORDER_BLOCK;
                yield null;
            }
            case SMC -> {
                if (msg.contains("CHOCH")) yield SignalWeight.CHOCH;
                if (msg.contains("BOS"))   yield SignalWeight.BOS;
                yield null;
            }
            case WAVETREND  -> SignalWeight.WAVETREND;
            case EQUAL_LEVEL -> SignalWeight.EQUAL_LEVEL;
            case FVG        -> SignalWeight.FVG;
            case SUPERTREND -> SignalWeight.SUPERTREND;
            case VWAP_CROSS -> SignalWeight.VWAP_CROSS;
            case EMA        -> SignalWeight.EMA;
            case MACD       -> SignalWeight.MACD;
            case CHAIKIN    -> SignalWeight.CHAIKIN;
            case DELTA_FLOW -> SignalWeight.DELTA_FLOW;
            case RSI        -> SignalWeight.RSI;
            default -> null;
        };
    }
}
