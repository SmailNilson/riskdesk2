package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a {@link StrategyDecision} into the nested Map/JSON shape the Mentor
 * payload expects under the {@code strategy_engine_analysis} key. Pure utility —
 * no Spring, no logging, no I/O. Keep it deterministic so the same decision
 * always produces the same JSON bytes (important for cache keys, test golden
 * files, and Gemini prompt stability).
 *
 * <h2>Payload shape</h2>
 * <pre>
 * {
 *   "candidate_playbook": "NOR",        // or null if no playbook applicable
 *   "decision": "HALF_SIZE",
 *   "direction": "LONG",                // or null
 *   "final_score": 72.3,
 *   "layer_scores": {
 *     "CONTEXT": 65.0,
 *     "ZONE":    78.0,
 *     "TRIGGER": 42.0
 *   },
 *   "veto_reasons": [],
 *   "agent_votes": [                    // sorted: veto > active > abstain, then by layer
 *     {"id":"smc-macro-bias", "layer":"CONTEXT", "vote":70, "confidence":0.85,
 *      "evidence":"Swing bias BULL on 1h"},
 *     ...
 *   ],
 *   "evaluated_at": "2026-04-17T10:00:00Z"
 * }
 * </pre>
 *
 * <p><b>Why not just serialise the record directly?</b> Two reasons. First, the
 * Mentor payload is a {@code Map<String, Object>} to stay flexible; embedding a
 * typed record would force us to leak strategy-package types through the JSON
 * serializer. Second, this shape is part of the Gemini prompt contract —
 * reordering or renaming fields affects the LLM's understanding, so we want an
 * explicit schema adapter that can evolve independently from
 * {@link StrategyDecision}'s internal layout.
 */
public final class StrategyEngineAnalysisPayload {

    private StrategyEngineAnalysisPayload() {
        throw new AssertionError("no instances");
    }

    /** Null-safe entry point — callers can always pass the result through. */
    public static Map<String, Object> build(StrategyDecision decision) {
        if (decision == null) return null;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidate_playbook", decision.candidatePlaybookId().orElse(null));
        out.put("decision", decision.decision().name());
        out.put("direction", decision.direction().map(Enum::name).orElse(null));
        out.put("final_score", roundScore(decision.finalScore()));
        out.put("layer_scores", layerScoresMap(decision));
        out.put("veto_reasons", decision.vetoReasons());
        out.put("agent_votes", agentVotes(decision.votes()));
        out.put("evaluated_at", decision.evaluatedAt().toString());
        return out;
    }

    private static Map<String, Object> layerScoresMap(StrategyDecision decision) {
        Map<String, Object> layerScores = new LinkedHashMap<>();
        // Deterministic order for Gemini stability — iterate enum values
        for (StrategyLayer layer : StrategyLayer.values()) {
            Double score = decision.layerScores().get(layer);
            layerScores.put(layer.name(), score == null ? 0.0 : roundScore(score));
        }
        return layerScores;
    }

    private static List<Map<String, Object>> agentVotes(List<AgentVote> votes) {
        List<AgentVote> sorted = new ArrayList<>(votes);
        // Priority order: veto first (most important signal), then active votes,
        // then abstains. Within each group, sort by layer so CONTEXT comes before
        // ZONE before TRIGGER — mirrors the top-down funnel the engine enforces.
        sorted.sort(Comparator
            .comparingInt(StrategyEngineAnalysisPayload::voteRank)
            .thenComparingInt(v -> v.layer().ordinal())
            .thenComparing(AgentVote::agentId));
        List<Map<String, Object>> out = new ArrayList<>(sorted.size());
        for (AgentVote v : sorted) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", v.agentId());
            entry.put("layer", v.layer().name());
            entry.put("vote", v.directionalVote());
            entry.put("confidence", round(v.confidence(), 2));
            entry.put("abstain", v.abstain());
            v.vetoReason().ifPresent(r -> entry.put("veto_reason", r));
            entry.put("evidence", String.join(" — ", v.evidence()));
            out.add(entry);
        }
        return out;
    }

    private static int voteRank(AgentVote v) {
        if (v.hasVeto())  return 0;
        if (v.abstain())  return 2;
        return 1;
    }

    /** Score to one decimal place — matches the precision operators see in the UI. */
    private static double roundScore(double v) {
        return round(v, 1);
    }

    private static double round(double v, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(v * scale) / scale;
    }
}
