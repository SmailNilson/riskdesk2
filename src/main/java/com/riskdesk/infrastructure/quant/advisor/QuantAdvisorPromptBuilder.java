package com.riskdesk.infrastructure.quant.advisor;

import com.riskdesk.domain.quant.advisor.MultiInstrumentContext;
import com.riskdesk.domain.quant.memory.MemoryRecord;
import com.riskdesk.domain.quant.memory.SessionMemory;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Loads the {@code quant-advisor.txt} prompt template from the classpath and
 * fills the {{placeholders}} with the current evaluation context. Plain string
 * substitution — no Mustache / Handlebars dependency.
 */
@Component
public class QuantAdvisorPromptBuilder {

    private static final String TEMPLATE_PATH = "prompts/quant-advisor.txt";

    private final String template;

    public QuantAdvisorPromptBuilder() {
        try {
            this.template = new String(
                new ClassPathResource(TEMPLATE_PATH).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + TEMPLATE_PATH, e);
        }
    }

    public String build(QuantSnapshot snapshot,
                         PatternAnalysis pattern,
                         SessionMemory memory,
                         List<MemoryRecord> similar,
                         MultiInstrumentContext multi,
                         int topK) {
        Map<String, String> vars = Map.ofEntries(
            Map.entry("instrument", snapshot.instrument().name()),
            Map.entry("score",      String.valueOf(snapshot.score())),
            Map.entry("price",      formatNullable(snapshot.price())),
            Map.entry("priceSource",snapshot.priceSource() == null ? "" : snapshot.priceSource()),
            Map.entry("dayMove",    String.format(java.util.Locale.US,"%+.0f", snapshot.dayMove())),
            Map.entry("gatesKo",    formatGatesKo(snapshot)),
            Map.entry("patternType",        pattern == null ? "INDETERMINE" : pattern.type().name()),
            Map.entry("patternConfidence",  pattern == null ? "LOW" : pattern.confidence().name()),
            Map.entry("patternReason",      pattern == null ? "n/a" : pattern.reason()),
            Map.entry("entry",      formatNullable(snapshot.suggestedEntry())),
            Map.entry("sl",         formatNullable(snapshot.suggestedSL())),
            Map.entry("tp1",        formatNullable(snapshot.suggestedTP1())),
            Map.entry("tp2",        formatNullable(snapshot.suggestedTP2())),
            Map.entry("multiInstrument",     formatMulti(multi)),
            Map.entry("patternsObserved",    formatPatternsObserved(memory)),
            Map.entry("tradesCount",         memory == null ? "0" : String.valueOf(memory.tradesExecuted())),
            Map.entry("winRate",             memory == null ? "0" : String.format(java.util.Locale.US,"%.0f", memory.winRate())),
            Map.entry("lastSetupOutcome",    memory == null ? "—" : memory.lastSetupOutcome()),
            Map.entry("recentAbnormalities", memory == null ? "—" : memory.recentAbnormalities()),
            Map.entry("topK",                String.valueOf(topK)),
            Map.entry("similarSituations",   formatSimilar(similar))
        );
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return out;
    }

    private static String formatNullable(Double v) {
        return v == null ? "—" : String.format(java.util.Locale.US,"%.2f", v);
    }

    private static String formatGatesKo(QuantSnapshot snap) {
        return snap.gates().entrySet().stream()
            .filter(e -> !e.getValue().ok())
            .map(e -> e.getKey().name() + " (" + e.getValue().reason() + ")")
            .collect(Collectors.joining(" | "));
    }

    private static String formatMulti(MultiInstrumentContext multi) {
        if (multi == null || multi.instruments().isEmpty()) {
            return "(aucune donnée multi-instrument)";
        }
        return multi.instruments().stream()
            .map(i -> String.format(java.util.Locale.US,"- %s: score %d/7 @ %.2f (Δjour %+.0fpts)",
                i.instrument().name(), i.score(),
                i.price() == null ? Double.NaN : i.price(),
                i.dayMove()))
            .collect(Collectors.joining("\n"));
    }

    private static String formatPatternsObserved(SessionMemory memory) {
        if (memory == null || memory.patternsObserved().isEmpty()) return "(aucun)";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<OrderFlowPattern, Integer> e : memory.patternsObserved().entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey().name()).append('×').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    private static String formatSimilar(List<MemoryRecord> similar) {
        if (similar == null || similar.isEmpty()) {
            return "(aucune situation similaire en mémoire)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < similar.size(); i++) {
            MemoryRecord r = similar.get(i);
            sb.append(i + 1).append(". ")
              .append(r.ts() == null ? "?" : r.ts().toString()).append(" setup ").append(r.score()).append("/7")
              .append(" → ").append(r.outcome())
              .append(" (").append(String.format(java.util.Locale.US,"%+.0f", r.ptsResult())).append("pts)\n")
              .append("   pattern=").append(r.pattern() == null ? "?" : r.pattern().name())
              .append(" notes=").append(r.notes() == null ? "" : r.notes())
              .append('\n');
        }
        return sb.toString();
    }

    /** Convenience for diagnostics / tests. */
    public String rawTemplate() {
        return template;
    }

    /** Used by the adapter for the audit log payload (cheap hash). */
    public static String hash(String prompt) {
        return Integer.toHexString(prompt.hashCode());
    }

    /** Avoids a hard dep on Gate import for callers. */
    static Map<Gate, GateResult> safeGates(QuantSnapshot snap) {
        return snap.gates();
    }
}
