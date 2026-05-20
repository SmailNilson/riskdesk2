package com.riskdesk.presentation.quant.dto;

import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.structure.StructuralBlock;
import com.riskdesk.domain.quant.structure.StructuralWarning;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire representation of one Quant evaluation tick. Field names are part of
 * the public API contract — the frontend dashboard reads them by name.
 *
 * <p>Since PR #299 the response also exposes the SHORT structural filter
 * result. Since the LONG-symmetry slice the response also exposes the LONG
 * track ({@code longScore}, {@code longEntry/sl/tp1/tp2}, {@code longGates}
 * implicitly through the same {@link #gates} list with {@code L*} entries,
 * {@code longStructuralBlocks/Warnings/ScoreModifier/finalScore}, {@code longBlocked},
 * {@code longAvailable}). When the structural evaluator has nothing to say
 * the additional fields default to empty / 0 / false, preserving the
 * pre-LONG-symmetry frontend contract.</p>
 */
public record QuantSnapshotResponse(
    String instrument,
    int score,
    int longScore,
    Double price,
    String priceSource,
    double dayMove,
    String scanTime,
    Double entry,
    Double sl,
    Double tp1,
    Double tp2,
    Double longEntry,
    Double longSl,
    Double longTp1,
    Double longTp2,
    boolean shortSetup7_7,
    boolean shortAlert6_7,
    boolean longSetup7_7,
    boolean longAlert6_7,
    List<QuantGateView> gates,
    // ── SHORT structural filters (PR #299) ──────────────────────────────
    List<StructuralBlockView> structuralBlocks,
    List<StructuralWarningView> structuralWarnings,
    int structuralScoreModifier,
    int finalScore,
    boolean shortBlocked,
    boolean shortAvailable,
    // ── LONG structural filters (LONG-symmetry slice) ───────────────────
    List<StructuralBlockView> longStructuralBlocks,
    List<StructuralWarningView> longStructuralWarnings,
    int longStructuralScoreModifier,
    int longFinalScore,
    boolean longBlocked,
    boolean longAvailable
) {

    public static QuantSnapshotResponse from(QuantSnapshot snapshot) {
        List<QuantGateView> gateList = new ArrayList<>(Gate.values().length);
        for (Gate g : Gate.values()) {
            var r = snapshot.gates().get(g);
            if (r == null) continue;
            gateList.add(new QuantGateView(g.name(), r.ok(), r.reason()));
        }
        List<StructuralBlockView> blocks = new ArrayList<>(snapshot.structuralBlocks().size());
        for (StructuralBlock b : snapshot.structuralBlocks()) {
            blocks.add(new StructuralBlockView(b.code(), b.evidence()));
        }
        List<StructuralWarningView> warnings = new ArrayList<>(snapshot.structuralWarnings().size());
        for (StructuralWarning w : snapshot.structuralWarnings()) {
            warnings.add(new StructuralWarningView(w.code(), w.evidence(), w.scoreModifier()));
        }
        List<StructuralBlockView> longBlocks = new ArrayList<>(snapshot.longStructuralBlocks().size());
        for (StructuralBlock b : snapshot.longStructuralBlocks()) {
            longBlocks.add(new StructuralBlockView(b.code(), b.evidence()));
        }
        List<StructuralWarningView> longWarnings = new ArrayList<>(snapshot.longStructuralWarnings().size());
        for (StructuralWarning w : snapshot.longStructuralWarnings()) {
            longWarnings.add(new StructuralWarningView(w.code(), w.evidence(), w.scoreModifier()));
        }
        return new QuantSnapshotResponse(
            snapshot.instrument().name(),
            snapshot.score(),
            snapshot.longScore(),
            snapshot.price(),
            snapshot.priceSource(),
            snapshot.dayMove(),
            snapshot.scanTime() != null ? snapshot.scanTime().toString() : null,
            snapshot.suggestedEntry(),
            snapshot.suggestedSL(),
            snapshot.suggestedTP1(),
            snapshot.suggestedTP2(),
            snapshot.suggestedEntry(),
            snapshot.suggestedSL_LONG(),
            snapshot.suggestedTP1_LONG(),
            snapshot.suggestedTP2_LONG(),
            snapshot.isShortSetup7_7(),
            snapshot.isShortAlert6_7(),
            snapshot.isLongSetup7_7(),
            snapshot.isLongAlert6_7(),
            gateList,
            blocks,
            warnings,
            snapshot.structuralScoreModifier(),
            snapshot.finalScore(),
            snapshot.shortBlocked(),
            snapshot.shortAvailable(),
            longBlocks,
            longWarnings,
            snapshot.longStructuralScoreModifier(),
            snapshot.longFinalScore(),
            snapshot.longBlocked(),
            snapshot.longAvailable()
        );
    }

    /** Wire view of a {@link StructuralBlock}. */
    public record StructuralBlockView(String code, String evidence) {}

    /** Wire view of a {@link StructuralWarning}. */
    public record StructuralWarningView(String code, String evidence, int scoreModifier) {}
}
