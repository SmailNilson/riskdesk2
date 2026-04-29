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

    /** Resolves the pattern + narration for {@code snapshot}, using the recent in-memory history.
     *  <p>
     *  The current snapshot's price is always appended to the recent window passed to the pattern
     *  detector — even when the snapshot has not yet been added to {@link QuantSnapshotHistoryStore}.
     *  This guarantees that the regime classification (ABSORPTION_HAUSSIERE, VRAIE_VENTE, …) sees
     *  the latest tick and stays consistent regardless of the caller's add-to-history timing
     *  (Codex review on PR #299 — structural evaluator must not consume a stale pattern).
     */
    public NarrationResult buildNarration(Instrument instrument,
                                          QuantSnapshot snapshot,
                                          QuantState state,
                                          com.riskdesk.domain.quant.model.MarketSnapshot marketSnapshot) {
        List<Double> recentPrices = recentPricesIncluding(instrument, snapshot);
        PatternAnalysis pattern = patternDetector.detect(marketSnapshot, state, recentPrices);
        String markdown = narrator.narrate(instrument, snapshot, pattern);
        return new NarrationResult(pattern, markdown);
    }

    private List<Double> recentPricesIncluding(Instrument instrument, QuantSnapshot current) {
        List<QuantSnapshot> history = historyStore.recent(instrument, java.time.Duration.ofMinutes(15));
        // PRICE_HISTORY_DEPTH-1 from history + current price = PRICE_HISTORY_DEPTH total. Falling
        // back to whatever history is available when shorter (cold start, scheduler stall…).
        int keepFromHistory = Math.max(0, PRICE_HISTORY_DEPTH - 1);
        int from = Math.max(0, history.size() - keepFromHistory);
        List<Double> out = new ArrayList<>(PRICE_HISTORY_DEPTH);
        for (int i = from; i < history.size(); i++) {
            Double p = history.get(i).price();
            if (p != null) out.add(p);
        }
        // Always include the current tick so the pattern detector sees the most recent price,
        // even when the orchestrator hasn't yet called historyStore.add(current).
        if (current != null && current.price() != null) {
            // Avoid double-counting if the orchestrator already added the snapshot to history
            // before calling buildNarration (defensive — current contract is "add after").
            if (out.isEmpty() || !out.get(out.size() - 1).equals(current.price())) {
                out.add(current.price());
            }
        }
        return out;
    }

    /** Bundle returned to the orchestrator so the pattern can be reused (e.g. for the AI advisor). */
    public record NarrationResult(PatternAnalysis pattern, String markdown) {}
}
