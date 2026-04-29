package com.riskdesk.application.quant.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.model.QuantState;
import com.riskdesk.domain.quant.narrative.QuantNarrator;
import com.riskdesk.domain.quant.pattern.OrderFlowPatternDetector;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Glue between the gate evaluator output and the human-readable narration
 * shown in the dashboard. Resolves the order-flow pattern from the most
 * recent snapshots in {@link QuantSnapshotHistoryStore}, runs the pure
 * {@link QuantNarrator} and returns both the pattern + the markdown.
 */
@Service
public class QuantSetupNarrationService {

    /** Number of recent snapshots fed to the pattern detector. */
    public static final int PRICE_HISTORY_DEPTH = 3;

    private final QuantSnapshotHistoryStore historyStore;
    private final OrderFlowPatternDetector patternDetector;
    private final QuantNarrator narrator;

    public QuantSetupNarrationService(QuantSnapshotHistoryStore historyStore,
                                      OrderFlowPatternDetector patternDetector,
                                      QuantNarrator narrator) {
        this.historyStore = historyStore;
        this.patternDetector = patternDetector;
        this.narrator = narrator;
    }

    /** Resolves the pattern + narration for {@code snapshot}, using the recent in-memory history. */
    public NarrationResult buildNarration(Instrument instrument,
                                          QuantSnapshot snapshot,
                                          QuantState state,
                                          com.riskdesk.domain.quant.model.MarketSnapshot marketSnapshot) {
        List<Double> recentPrices = recentPrices(instrument);
        PatternAnalysis pattern = patternDetector.detect(marketSnapshot, state, recentPrices);
        String markdown = narrator.narrate(instrument, snapshot, pattern);
        return new NarrationResult(pattern, markdown);
    }

    private List<Double> recentPrices(Instrument instrument) {
        List<QuantSnapshot> history = historyStore.recent(instrument, java.time.Duration.ofMinutes(15));
        if (history.isEmpty()) return List.of();
        int from = Math.max(0, history.size() - PRICE_HISTORY_DEPTH);
        List<Double> out = new ArrayList<>(PRICE_HISTORY_DEPTH);
        for (int i = from; i < history.size(); i++) {
            Double p = history.get(i).price();
            if (p != null) out.add(p);
        }
        return out;
    }

    /** Bundle returned to the orchestrator so the pattern can be reused (e.g. for the AI advisor). */
    public record NarrationResult(PatternAnalysis pattern, String markdown) {}
}
