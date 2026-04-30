package com.riskdesk.domain.quant.model;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.structure.StructuralBlock;
import com.riskdesk.domain.quant.structure.StructuralFilterResult;
import com.riskdesk.domain.quant.structure.StructuralWarning;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Output of one Quant evaluation tick. Carries BOTH directions side-by-side:
 * the SHORT 7-gate verdict (G0–G6) and the symmetric LONG verdict (L0–L6)
 * added in the LONG-symmetry slice. The {@link #gates} map contains all
 * 14 entries — {@link #score} counts the SHORT subset, {@link #longScore}
 * counts the LONG subset.
 *
 * <p>Suggestion formula mirrors the Python reference:</p>
 * <pre>
 *   SHORT: ENTRY=price  SL=price+25  TP1=price-40  TP2=price-80
 *   LONG : ENTRY=price  SL=price-25  TP1=price+40  TP2=price+80
 * </pre>
 *
 * <p>Structural filters: PR #299 added the SHORT track ({@link #structuralBlocks},
 * {@link #structuralWarnings}, {@link #structuralScoreModifier}, {@link #shortBlocked}).
 * The LONG-symmetry slice adds the matching {@code longStructuralBlocks},
 * {@code longStructuralWarnings}, {@code longStructuralScoreModifier} and
 * {@code longBlocked} fields evaluated by the same {@code StructuralFilterEvaluator}
 * but from the LONG perspective (e.g. fresh BEARISH OB blocks LONG, MTF_BEAR
 * blocks LONG, CMF_VERY_BEAR blocks LONG).</p>
 *
 * @param instrument                       the scanned future
 * @param gates                            per-gate verdicts (14 entries: G0–G6 + L0–L6)
 * @param score                            number of SHORT gates that passed (0–7)
 * @param longScore                        number of LONG gates that passed (0–7)
 * @param price                            live price at scan time, may be {@code null}
 * @param priceSource                      origin of the price
 * @param dayMove                          points moved since {@code monitorStartPx}
 * @param scanTime                         evaluation timestamp in America/New_York
 * @param structuralBlocks                 structural blocks vetoing SHORT
 * @param structuralWarnings               structural warnings against SHORT (score modifiers)
 * @param structuralScoreModifier          cumulative SHORT warning modifier (≤ 0)
 * @param shortBlocked                     {@code true} if any SHORT block fired
 * @param longStructuralBlocks             structural blocks vetoing LONG
 * @param longStructuralWarnings           structural warnings against LONG (score modifiers)
 * @param longStructuralScoreModifier      cumulative LONG warning modifier (≤ 0)
 * @param longBlocked                      {@code true} if any LONG block fired
 */
public record QuantSnapshot(
    Instrument instrument,
    Map<Gate, GateResult> gates,
    int score,
    int longScore,
    Double price,
    String priceSource,
    double dayMove,
    ZonedDateTime scanTime,
    List<StructuralBlock> structuralBlocks,
    List<StructuralWarning> structuralWarnings,
    int structuralScoreModifier,
    boolean shortBlocked,
    List<StructuralBlock> longStructuralBlocks,
    List<StructuralWarning> longStructuralWarnings,
    int longStructuralScoreModifier,
    boolean longBlocked
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
        longStructuralBlocks   = longStructuralBlocks   == null ? List.of() : List.copyOf(longStructuralBlocks);
        longStructuralWarnings = longStructuralWarnings == null ? List.of() : List.copyOf(longStructuralWarnings);
    }

    /**
     * Backward-compat ctor (pre-LONG-symmetry shape, with structural filters).
     * Defaults all LONG fields to empty / 0 / false.
     */
    public QuantSnapshot(Instrument instrument, Map<Gate, GateResult> gates, int score,
                          Double price, String priceSource, double dayMove,
                          ZonedDateTime scanTime,
                          List<StructuralBlock> structuralBlocks,
                          List<StructuralWarning> structuralWarnings,
                          int structuralScoreModifier,
                          boolean shortBlocked) {
        this(instrument, gates, score, 0, price, priceSource, dayMove, scanTime,
             structuralBlocks, structuralWarnings, structuralScoreModifier, shortBlocked,
             List.of(), List.of(), 0, false);
    }

    /**
     * Backward-compat ctor for callers that don't (yet) compute the structural
     * filters — equivalent to passing empty lists / 0 modifier / not blocked
     * for both directions. Keeps pre-PR #299 callers and tests intact.
     */
    public QuantSnapshot(Instrument instrument, Map<Gate, GateResult> gates, int score,
                          Double price, String priceSource, double dayMove,
                          ZonedDateTime scanTime) {
        this(instrument, gates, score, 0, price, priceSource, dayMove, scanTime,
             List.of(), List.of(), 0, false,
             List.of(), List.of(), 0, false);
    }

    /**
     * Constructor for the post-LONG-symmetry {@code GateEvaluator} — both
     * scores supplied, no structural data yet (structural is attached later
     * via {@link #withStructuralResult}/{@link #withLongStructuralResult}).
     */
    public QuantSnapshot(Instrument instrument, Map<Gate, GateResult> gates, int score, int longScore,
                          Double price, String priceSource, double dayMove,
                          ZonedDateTime scanTime) {
        this(instrument, gates, score, longScore, price, priceSource, dayMove, scanTime,
             List.of(), List.of(), 0, false,
             List.of(), List.of(), 0, false);
    }

    /** New copy with the SHORT structural filter result attached. */
    public QuantSnapshot withStructuralResult(StructuralFilterResult sr) {
        if (sr == null) return this;
        return new QuantSnapshot(instrument, gates, score, longScore, price, priceSource, dayMove, scanTime,
            sr.blocks(), sr.warnings(), sr.scoreModifier(), sr.shortBlocked(),
            longStructuralBlocks, longStructuralWarnings, longStructuralScoreModifier, longBlocked);
    }

    /** New copy with the LONG structural filter result attached. */
    public QuantSnapshot withLongStructuralResult(StructuralFilterResult sr) {
        if (sr == null) return this;
        return new QuantSnapshot(instrument, gates, score, longScore, price, priceSource, dayMove, scanTime,
            structuralBlocks, structuralWarnings, structuralScoreModifier, shortBlocked,
            sr.blocks(), sr.warnings(), sr.scoreModifier(), sr.shortBlocked());
    }

    public boolean isShortSetup7_7() {
        return score >= FULL_SETUP_SCORE;
    }

    public boolean isShortAlert6_7() {
        return score >= EARLY_SETUP_SCORE && score < FULL_SETUP_SCORE;
    }

    public boolean isLongSetup7_7() {
        return longScore >= FULL_SETUP_SCORE;
    }

    public boolean isLongAlert6_7() {
        return longScore >= EARLY_SETUP_SCORE && longScore < FULL_SETUP_SCORE;
    }

    /** Raw 7-gate SHORT score adjusted by the SHORT structural warnings modifier. Bounded below by 0. */
    public int finalScore() {
        return Math.max(0, score + structuralScoreModifier);
    }

    /** LONG mirror of {@link #finalScore()}. */
    public int longFinalScore() {
        return Math.max(0, longScore + longStructuralScoreModifier);
    }

    /**
     * SHORT is available when the gate threshold is met AND no structural
     * BLOCK fired. Used by the frontend to decide whether to show an
     * "executable" plan or a "blocked" banner.
     */
    public boolean shortAvailable() {
        return !shortBlocked && score >= EARLY_SETUP_SCORE;
    }

    /** LONG mirror of {@link #shortAvailable()}. */
    public boolean longAvailable() {
        return !longBlocked && longScore >= EARLY_SETUP_SCORE;
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

    /** LONG mirror of {@link #suggestedSL()} — protective stop below entry. */
    public Double suggestedSL_LONG() {
        return price == null ? null : price - SL_OFFSET;
    }

    /** LONG mirror of {@link #suggestedTP1()} — first take-profit above entry. */
    public Double suggestedTP1_LONG() {
        return price == null ? null : price + TP1_OFFSET;
    }

    /** LONG mirror of {@link #suggestedTP2()} — extended take-profit above entry. */
    public Double suggestedTP2_LONG() {
        return price == null ? null : price + TP2_OFFSET;
    }
}
