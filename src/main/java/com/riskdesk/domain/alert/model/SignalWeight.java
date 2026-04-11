package com.riskdesk.domain.alert.model;

import java.util.Locale;
import java.util.Set;

/**
 * Confluence weight, family, and structural priority for each qualified signal type.
 *
 * <p><b>Arm &amp; Fire model:</b> Signals with weight 3.0 ("standalone") ARM a HTF buffer
 * on their own. ORDER_BLOCK, CHoCH, and BOS qualify — they represent structural
 * events with high conviction. Signals at 2.5 ("near-standalone") need one secondary
 * signal (+1.0) to reach the flush threshold. Signals at 2.0 need at least one
 * confirming signal. Signals at 1.0 require broader confluence.
 *
 * <p>Non-cumul rule: within families {@code Momentum} and {@code Flow},
 * only the maximum weight counts (EMA + MACD = 1.0, not 2.0).
 */
public enum SignalWeight {

    ORDER_BLOCK(3.0f, "Structure",       1),
    CHOCH      (3.0f, "SMC",             2),
    WAVETREND  (2.5f, "Oscillateur",     3),   // near-standalone: needs +1.0 secondary to flush
    BOS        (3.0f, "SMC",             4),   // standalone: structural break, 100% win rate in backtest
    CHAIKIN    (2.0f, "Flow",            5),   // downgraded: flow oscillator, needs structure
    EQUAL_LEVEL(1.0f, "Liquidite",       6),
    FVG        (1.0f, "Structure_FVG",   7),
    SUPERTREND (1.0f, "Tendance",        8),
    VWAP_CROSS (1.0f, "Niveaux",         9),
    EMA        (1.0f, "Momentum",       10),
    MACD       (1.0f, "Momentum",       11),
    DELTA_FLOW (1.0f, "Flow",           12),
    RSI        (1.0f, "Oscillateur_RSI", 13),
    STOCHASTIC (1.0f, "Oscillateur_Stoch", 14),
    /** Institutional absorption — real order flow. Separate "OrderFlow" family (not "Flow") avoids non-cumul collision. */
    ABSORPTION      (2.0f, "OrderFlow", 15),
    /** Real delta oscillator: EMA(3)-EMA(10) of tick-by-tick delta. */
    DELTA_OSCILLATOR(1.5f, "OrderFlow", 16);

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
            case STOCHASTIC       -> SignalWeight.STOCHASTIC;
            case ABSORPTION       -> SignalWeight.ABSORPTION;
            case DELTA_OSCILLATOR -> SignalWeight.DELTA_OSCILLATOR;
            default -> null;
        };
    }
}
