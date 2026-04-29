package com.riskdesk.domain.quant.structure;

import java.util.List;

/**
 * Aggregate output of {@link StructuralFilterEvaluator}: the structural
 * blocks (kill-switch SHORT), the warnings (score modifiers, displayed),
 * the cumulative {@link #scoreModifier} and a convenience {@link #shortBlocked}
 * flag derived from {@code !blocks.isEmpty()}.
 */
public record StructuralFilterResult(
    List<StructuralBlock> blocks,
    List<StructuralWarning> warnings,
    int scoreModifier,
    boolean shortBlocked
) {
    public StructuralFilterResult {
        blocks   = blocks   == null ? List.of() : List.copyOf(blocks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** Empty (no structural data available) — same as "everything green, no modifier". */
    public static StructuralFilterResult empty() {
        return new StructuralFilterResult(List.of(), List.of(), 0, false);
    }
}
