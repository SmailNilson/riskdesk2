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
 * <p>Since PR #299 the response also exposes the structural filter result
 * ({@code structuralBlocks}, {@code structuralWarnings}, {@code structuralScoreModifier},
 * {@code finalScore}, {@code shortAvailable}). When the structural evaluator
 * has nothing to say (no indicator data, no blocks, no warnings) the
 * additional fields default to empty / 0 / true, preserving the pre-#299
 * frontend contract.</p>
 */
public record QuantSnapshotResponse(
    String instrument,
    int score,
    Double price,
    String priceSource,
    double dayMove,
    String scanTime,
    Double entry,
    Double sl,
    Double tp1,
    Double tp2,
    boolean shortSetup7_7,
    boolean shortAlert6_7,
    List<QuantGateView> gates,
    // ── Structural filters (PR #299) ────────────────────────────────────
    List<StructuralBlockView> structuralBlocks,
    List<StructuralWarningView> structuralWarnings,
    int structuralScoreModifier,
    int finalScore,
    boolean shortBlocked,
    boolean shortAvailable
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
        return new QuantSnapshotResponse(
            snapshot.instrument().name(),
            snapshot.score(),
            snapshot.price(),
            snapshot.priceSource(),
            snapshot.dayMove(),
            snapshot.scanTime() != null ? snapshot.scanTime().toString() : null,
            snapshot.suggestedEntry(),
            snapshot.suggestedSL(),
            snapshot.suggestedTP1(),
            snapshot.suggestedTP2(),
            snapshot.isShortSetup7_7(),
            snapshot.isShortAlert6_7(),
            gateList,
            blocks,
            warnings,
            snapshot.structuralScoreModifier(),
            snapshot.finalScore(),
            snapshot.shortBlocked(),
            snapshot.shortAvailable()
        );
    }

    /** Wire view of a {@link StructuralBlock}. */
    public record StructuralBlockView(String code, String evidence) {}

    /** Wire view of a {@link StructuralWarning}. */
    public record StructuralWarningView(String code, String evidence, int scoreModifier) {}
}
