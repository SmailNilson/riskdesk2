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

        // If the orchestrator already called historyStore.add(current) before this method (e.g.
        // the second narration pass in QuantGateService.scan), the tail of history IS the current
        // tick. Drop it so we rebuild the window from the *prior* scans, then re-append current —
        // guaranteeing both passes produce the SAME PRICE_HISTORY_DEPTH-sized window. Identity is
        // tracked by scanTime, not by price value: two consecutive scans can legitimately share a
        // price (flat market) but never share a scanTime (assigned by the scheduler per scan).
        if (current != null && current.scanTime() != null && !history.isEmpty()) {
            QuantSnapshot tail = history.get(history.size() - 1);
            if (tail != null && current.scanTime().equals(tail.scanTime())) {
                history = history.subList(0, history.size() - 1);
            }
        }

        // Take the last (DEPTH-1) prior snapshots, then always append the current tick → exactly
        // PRICE_HISTORY_DEPTH prices when history is warm, fewer on cold start (the detector's
        // {@code recentPrices.size() >= 2} guard handles short windows by falling back to the
        // single-scan classifier).
        int keepFromHistory = Math.max(0, PRICE_HISTORY_DEPTH - 1);
        int from = Math.max(0, history.size() - keepFromHistory);
        List<Double> out = new ArrayList<>(PRICE_HISTORY_DEPTH);
        for (int i = from; i < history.size(); i++) {
            Double p = history.get(i).price();
            if (p != null) out.add(p);
        }
        if (current != null && current.price() != null) {
            out.add(current.price());
        }
        return out;
    }

    /** Bundle returned to the orchestrator so the pattern can be reused (e.g. for the AI advisor). */
    public record NarrationResult(PatternAnalysis pattern, String markdown) {}
}
