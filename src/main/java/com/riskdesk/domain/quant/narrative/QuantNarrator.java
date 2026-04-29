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
 * plan) so a trader who knows the script feels at home reading the dashboard.
 */
public final class QuantNarrator {

    private static final Map<Gate, String> GATE_LABELS = new LinkedHashMap<>();
    static {
        GATE_LABELS.put(Gate.G0_REGIME,         "G0 Régime");
        GATE_LABELS.put(Gate.G1_ABS_BEAR,       "G1 ABS BEAR");
        GATE_LABELS.put(Gate.G2_DIST_PUR,       "G2 DIST_pur 2/3");
        GATE_LABELS.put(Gate.G3_DELTA_NEG,      "G3 Δ < -100");
        GATE_LABELS.put(Gate.G4_BUY_PCT_LOW,    "G4 buy% < 48");
        GATE_LABELS.put(Gate.G5_ACCU_THRESHOLD, "G5 ACCU seuil");
        GATE_LABELS.put(Gate.G6_LIVE_PUSH,      "G6 LIVE_PUSH");
    }

    public String narrate(Instrument instrument, QuantSnapshot snap, PatternAnalysis pattern) {
        StringBuilder md = new StringBuilder();
        md.append("## ").append(instrument.name()).append(" — Quant ").append(snap.score()).append("/7\n\n");
        md.append("**Prix** `").append(formatPrice(snap.price())).append("` ")
          .append("[`").append(snap.priceSource() == null ? "" : snap.priceSource()).append("`]  ")
          .append("**Δjour** `").append(String.format(Locale.US, "%+.1fpts", snap.dayMove())).append("`\n\n");

        md.append("### Gates\n");
        for (Map.Entry<Gate, String> e : GATE_LABELS.entrySet()) {
            GateResult r = snap.gates().get(e.getKey());
            if (r == null) continue;
            md.append("- ").append(r.ok() ? "✅" : "❌").append(" **")
              .append(e.getValue()).append("** — ").append(r.reason()).append("\n");
        }
        md.append("\n");

        if (pattern != null) {
            md.append("### Pattern order flow\n");
            md.append("`").append(pattern.type().name()).append("` **").append(pattern.label()).append("**  ")
              .append("(confidence: ").append(pattern.confidence().name()).append(")\n\n");
            md.append("> ").append(pattern.reason()).append("\n\n");
        }

        if (snap.isShortSetup7_7()) {
            md.append("### 🔔 SHORT 7/7 — exécutable\n");
            md.append(planBlock(snap));
        } else if (snap.isShortAlert6_7()) {
            md.append("### ⚠️ Setup 6/7 — early warning\n");
            String missing = snap.gates().entrySet().stream()
                .filter(en -> !en.getValue().ok())
                .map(en -> en.getKey().name())
                .collect(Collectors.joining(", "));
            md.append("Manque : `").append(missing).append("`\n\n");
            md.append(planBlock(snap));
        } else {
            md.append("### RAS — score ").append(snap.score()).append("/7\n");
        }
        return md.toString();
    }

    private String planBlock(QuantSnapshot snap) {
        return "```\n"
            + "ENTRY " + formatPrice(snap.suggestedEntry()) + "\n"
            + "SL    " + formatPrice(snap.suggestedSL())    + "\n"
            + "TP1   " + formatPrice(snap.suggestedTP1())   + "\n"
            + "TP2   " + formatPrice(snap.suggestedTP2())   + "\n"
            + "```\n";
    }

    private String formatPrice(Double v) {
        if (v == null) return "—";
        return String.format(Locale.US, "%.2f", v);
    }
}
