package com.riskdesk.domain.quant.model;

import com.riskdesk.domain.model.Instrument;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Output of the 7-gate evaluation for a single scan tick. Holds the per-gate
 * results, the aggregate score and (when the score is high enough) the
 * suggested entry / stop / take-profit levels for a SHORT setup.
 *
 * <p>The suggestion formula mirrors the Python reference:</p>
 * <pre>
 *   ENTRY = price
 *   SL    = price + 25
 *   TP1   = price - 40
 *   TP2   = price - 80
 * </pre>
 *
 * @param instrument the scanned future
 * @param gates      per-gate verdicts (always contains all seven entries)
 * @param score      number of gates that passed (0–7)
 * @param price      live price at scan time, may be {@code null} if no quote
 * @param priceSource origin of the price (forwarded for UI display)
 * @param dayMove    points moved since {@code monitorStartPx}
 * @param scanTime   evaluation timestamp in America/New_York for display
 */
public record QuantSnapshot(
    Instrument instrument,
    Map<Gate, GateResult> gates,
    int score,
    Double price,
    String priceSource,
    double dayMove,
    ZonedDateTime scanTime
) {
    /** SL offset (points) added to the live price for SHORT setups. */
    public static final double SL_OFFSET     = 25.0;
    /** TP1 offset (points) subtracted from the live price for SHORT setups. */
    public static final double TP1_OFFSET    = 40.0;
    /** TP2 offset (points) subtracted from the live price for SHORT setups. */
    public static final double TP2_OFFSET    = 80.0;
    /** Score threshold at which a full setup alert fires. */
    public static final int FULL_SETUP_SCORE  = 7;
    /** Score threshold for an early "setup forming" alert. */
    public static final int EARLY_SETUP_SCORE = 6;

    public QuantSnapshot {
        gates = gates == null ? Map.of() : Map.copyOf(gates);
    }

    public boolean isShortSetup7_7() {
        return score >= FULL_SETUP_SCORE;
    }

    public boolean isShortAlert6_7() {
        return score >= EARLY_SETUP_SCORE && score < FULL_SETUP_SCORE;
    }

    public Double suggestedEntry() {
        return price;
    }

    public Double suggestedSL() {
        return price == null ? null : price + SL_OFFSET;
    }

    public Double suggestedTP1() {
        return price == null ? null : price - TP1_OFFSET;
    }

    public Double suggestedTP2() {
        return price == null ? null : price - TP2_OFFSET;
    }
}
