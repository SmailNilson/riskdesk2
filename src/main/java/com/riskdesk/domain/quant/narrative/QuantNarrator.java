package com.riskdesk.domain.quant.narrative;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders the markdown narrative shown next to the gate panel. Output mirrors
 * the {@code mnq_monitor_v3.py} terminal printout (emojis, gate list, suggested
 * plan) for the SHORT side, and adds a symmetric LONG section so the trader
 * sees both directions in a single pane.
 */
public final class QuantNarrator {

    private static final Map<Gate, String> SHORT_GATE_LABELS = new LinkedHashMap<>();
    static {
        SHORT_GATE_LABELS.put(Gate.G0_REGIME,         "G0 Régime");
        SHORT_GATE_LABELS.put(Gate.G1_ABS_BEAR,       "G1 ABS BEAR");
        SHORT_GATE_LABELS.put(Gate.G2_DIST_PUR,       "G2 DIST_pur 2/3");
        SHORT_GATE_LABELS.put(Gate.G3_DELTA_NEG,      "G3 Δ < -100");
        SHORT_GATE_LABELS.put(Gate.G4_BUY_PCT_LOW,    "G4 buy% < 48");
        SHORT_GATE_LABELS.put(Gate.G5_ACCU_THRESHOLD, "G5 ACCU seuil");
        SHORT_GATE_LABELS.put(Gate.G6_LIVE_PUSH,      "G6 LIVE_PUSH");
    }

    private static final Map<Gate, String> LONG_GATE_LABELS = new LinkedHashMap<>();
    static {
        LONG_GATE_LABELS.put(Gate.L0_REGIME,         "L0 Régime");
        LONG_GATE_LABELS.put(Gate.L1_ABS_BULL,       "L1 ABS BULL");
        LONG_GATE_LABELS.put(Gate.L2_ACCU_PUR,       "L2 ACCU_pur 2/3");
        LONG_GATE_LABELS.put(Gate.L3_DELTA_POS,      "L3 Δ > +100");
        LONG_GATE_LABELS.put(Gate.L4_BUY_PCT_HIGH,   "L4 buy% > 52");
        LONG_GATE_LABELS.put(Gate.L5_DIST_THRESHOLD, "L5 DIST seuil");
        LONG_GATE_LABELS.put(Gate.L6_LIVE_PUSH,      "L6 LIVE_PUSH");
    }

    public String narrate(Instrument instrument, QuantSnapshot snap, PatternAnalysis pattern) {
        StringBuilder md = new StringBuilder();
        md.append("## ").append(instrument.name())
          .append(" — Quant SHORT ").append(snap.score()).append("/7")
          .append(" · LONG ").append(snap.longScore()).append("/7\n\n");
        md.append("**Prix** `").append(formatPrice(snap.price())).append("` ")
          .append("[`").append(snap.priceSource() == null ? "" : snap.priceSource()).append("`]  ")
          .append("**Δjour** `").append(String.format(Locale.US, "%+.1fpts", snap.dayMove())).append("`\n\n");

        if (pattern != null) {
            md.append("### Pattern order flow\n");
            md.append("`").append(pattern.type().name()).append("` **").append(pattern.label()).append("**  ")
              .append("(confidence: ").append(pattern.confidence().name()).append(")\n\n");
            md.append("> ").append(pattern.reason()).append("\n\n");
        }

        // ── SHORT track ──────────────────────────────────────────────────
        md.append("---\n\n");
        md.append("### SHORT — Gates\n");
        for (Map.Entry<Gate, String> e : SHORT_GATE_LABELS.entrySet()) {
            GateResult r = snap.gates().get(e.getKey());
            if (r == null) continue;
            md.append("- ").append(r.ok() ? "✅" : "❌").append(" **")
              .append(e.getValue()).append("** — ").append(r.reason()).append("\n");
        }
        md.append("\n");
        renderStructural(md, "SHORT", snap.structuralBlocks(), snap.structuralWarnings(),
                         snap.structuralScoreModifier());
        renderShortVerdict(md, snap);

        // ── LONG track ───────────────────────────────────────────────────
        md.append("\n---\n\n");
        md.append("### LONG — Gates\n");
        for (Map.Entry<Gate, String> e : LONG_GATE_LABELS.entrySet()) {
            GateResult r = snap.gates().get(e.getKey());
            if (r == null) continue;
            md.append("- ").append(r.ok() ? "✅" : "❌").append(" **")
              .append(e.getValue()).append("** — ").append(r.reason()).append("\n");
        }
        md.append("\n");
        renderStructural(md, "LONG", snap.longStructuralBlocks(), snap.longStructuralWarnings(),
                         snap.longStructuralScoreModifier());
        renderLongVerdict(md, snap);

        return md.toString();
    }

    private void renderStructural(StringBuilder md, String label,
                                   java.util.List<com.riskdesk.domain.quant.structure.StructuralBlock> blocks,
                                   java.util.List<com.riskdesk.domain.quant.structure.StructuralWarning> warnings,
                                   int modifier) {
        if (!blocks.isEmpty()) {
            md.append("#### 🚫 ").append(label).append(" structural blocks\n");
            for (var b : blocks) {
                md.append("- **").append(b.code()).append("** — ").append(b.evidence()).append("\n");
            }
            md.append("\n");
        }
        if (!warnings.isEmpty()) {
            md.append("#### ⚠️ ").append(label).append(" warnings (score ")
              .append(String.format(Locale.US, "%+d", modifier)).append(")\n");
            for (var w : warnings) {
                md.append("- `")
                  .append(String.format(Locale.US, "%+d", w.scoreModifier()))
                  .append("` **").append(w.code()).append("** — ").append(w.evidence()).append("\n");
            }
            md.append("\n");
        }
    }

    private void renderShortVerdict(StringBuilder md, QuantSnapshot snap) {
        if (snap.shortBlocked()) {
            md.append("#### ❌ SHORT bloqué — ").append(snap.structuralBlocks().size())
              .append(snap.structuralBlocks().size() == 1 ? " block" : " blocks").append(" structurel(s)\n");
        } else if (snap.isShortSetup7_7()) {
            md.append("#### 🔔 SHORT 7/7 — exécutable\n");
            md.append(shortPlanBlock(snap));
        } else if (snap.isShortAlert6_7()) {
            md.append("#### ⚠️ SHORT setup 6/7 — early warning\n");
            String missing = SHORT_GATE_LABELS.keySet().stream()
                .filter(g -> {
                    GateResult r = snap.gates().get(g);
                    return r != null && !r.ok();
                })
                .map(Gate::name)
                .collect(Collectors.joining(", "));
            md.append("Manque : `").append(missing).append("`\n\n");
            md.append(shortPlanBlock(snap));
        } else {
            md.append("#### RAS SHORT — score ").append(snap.score()).append("/7\n");
        }
    }

    private void renderLongVerdict(StringBuilder md, QuantSnapshot snap) {
        if (snap.longBlocked()) {
            md.append("#### ❌ LONG bloqué — ").append(snap.longStructuralBlocks().size())
              .append(snap.longStructuralBlocks().size() == 1 ? " block" : " blocks").append(" structurel(s)\n");
        } else if (snap.isLongSetup7_7()) {
            md.append("#### 🔔 LONG 7/7 — exécutable\n");
            md.append(longPlanBlock(snap));
        } else if (snap.isLongAlert6_7()) {
            md.append("#### ⚠️ LONG setup 6/7 — early warning\n");
            String missing = LONG_GATE_LABELS.keySet().stream()
                .filter(g -> {
                    GateResult r = snap.gates().get(g);
                    return r != null && !r.ok();
                })
                .map(Gate::name)
                .collect(Collectors.joining(", "));
            md.append("Manque : `").append(missing).append("`\n\n");
            md.append(longPlanBlock(snap));
        } else {
            md.append("#### RAS LONG — score ").append(snap.longScore()).append("/7\n");
        }
    }

    private String shortPlanBlock(QuantSnapshot snap) {
        return "```\n"
            + "ENTRY " + formatPrice(snap.suggestedEntry()) + "\n"
            + "SL    " + formatPrice(snap.suggestedSL())    + "\n"
            + "TP1   " + formatPrice(snap.suggestedTP1())   + "\n"
            + "TP2   " + formatPrice(snap.suggestedTP2())   + "\n"
            + "```\n";
    }

    private String longPlanBlock(QuantSnapshot snap) {
        return "```\n"
            + "ENTRY " + formatPrice(snap.suggestedEntry())     + "\n"
            + "SL    " + formatPrice(snap.suggestedSL_LONG())   + "\n"
            + "TP1   " + formatPrice(snap.suggestedTP1_LONG())  + "\n"
            + "TP2   " + formatPrice(snap.suggestedTP2_LONG())  + "\n"
            + "```\n";
    }

    private String formatPrice(Double v) {
        if (v == null) return "—";
        return String.format(Locale.US, "%.2f", v);
    }
}
