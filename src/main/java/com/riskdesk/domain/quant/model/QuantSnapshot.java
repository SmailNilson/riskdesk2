package com.riskdesk.domain.quant.model;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.structure.StructuralBlock;
import com.riskdesk.domain.quant.structure.StructuralFilterResult;
import com.riskdesk.domain.quant.structure.StructuralWarning;

import java.time.ZonedDateTime;
import java.util.List;
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
 * <p>Since PR #299 the snapshot also carries the structural filter result
 * (BLOCKS that veto the SHORT and WARNINGS that nudge the score down). The
 * raw 7-gate {@link #score} is unchanged; {@link #finalScore()} adds the
 * structural modifier and {@link #shortAvailable()} combines the gate
 * threshold with {@code !shortBlocked}.</p>
 *
 * @param instrument          the scanned future
 * @param gates               per-gate verdicts (always contains all seven entries)
 * @param score               number of gates that passed (0–7)
 * @param price               live price at scan time, may be {@code null} if no quote
 * @param priceSource         origin of the price (forwarded for UI display)
 * @param dayMove             points moved since {@code monitorStartPx}
 * @param scanTime            evaluation timestamp in America/New_York for display
 * @param structuralBlocks    structural filter blocks (kill-switch SHORT)
 * @param structuralWarnings  structural filter warnings (score modifiers)
 * @param structuralScoreModifier  cumulative warning score modifier (≤ 0)
 * @param shortBlocked        {@code true} if any block fired (SHORT vetoed)
 */
public record QuantSnapshot(
    Instrument instrument,
    Map<Gate, GateResult> gates,
    int score,
    Double price,
    String priceSource,
    double dayMove,
    ZonedDateTime scanTime,
    List<StructuralBlock> structuralBlocks,
    List<StructuralWarning> structuralWarnings,
    int structuralScoreModifier,
    boolean shortBlocked
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
        structuralBlocks   = structuralBlocks   == null ? List.of() : List.copyOf(structuralBlocks);
        structuralWarnings = structuralWarnings == null ? List.of() : List.copyOf(structuralWarnings);
    }

    /**
     * Compact constructor for callers that don't (yet) compute the structural
     * filters — equivalent to passing empty lists / 0 modifier / not blocked.
     * Keeps the existing test suites and the {@link GateEvaluator}'s pre-PR
     * #299 contract intact.
     */
    public QuantSnapshot(Instrument instrument, Map<Gate, GateResult> gates, int score,
                          Double price, String priceSource, double dayMove,
                          ZonedDateTime scanTime) {
        this(instrument, gates, score, price, priceSource, dayMove, scanTime,
             List.of(), List.of(), 0, false);
    }

    /** New copy with the structural filter result attached. */
    public QuantSnapshot withStructuralResult(StructuralFilterResult sr) {
        if (sr == null) return this;
        return new QuantSnapshot(instrument, gates, score, price, priceSource, dayMove, scanTime,
            sr.blocks(), sr.warnings(), sr.scoreModifier(), sr.shortBlocked());
    }

    public boolean isShortSetup7_7() {
        return score >= FULL_SETUP_SCORE;
    }

    public boolean isShortAlert6_7() {
        return score >= EARLY_SETUP_SCORE && score < FULL_SETUP_SCORE;
    }

    /** Raw 7-gate score adjusted by the structural warnings modifier. Bounded below by 0. */
    public int finalScore() {
        return Math.max(0, score + structuralScoreModifier);
    }

    /**
     * SHORT is available when the gate threshold is met AND no structural
     * BLOCK fired. Used by the frontend to decide whether to show an
     * "executable" plan or a "blocked" banner.
     */
    public boolean shortAvailable() {
        return !shortBlocked && score >= EARLY_SETUP_SCORE;
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
