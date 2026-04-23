package com.riskdesk.domain.engine.strategy.model;

import java.util.List;
import java.util.Optional;

/**
 * A single agent's contribution to the aggregate score.
 *
 * <p>Replaces the legacy {@link com.riskdesk.domain.engine.playbook.agent.AgentVerdict}'s
 * HIGH/MEDIUM/LOW enum with an explicit probabilistic representation: a
 * {@code directionalVote} on [-100, +100] separated from a {@code confidence} on [0, 1].
 *
 * <p><b>Abstain vs neutral vote:</b>
 * <ul>
 *   <li>{@code abstain = true} → this agent had insufficient data. The aggregator
 *       drops the vote entirely — it does NOT pull the score toward zero.</li>
 *   <li>{@code abstain = false, directionalVote = 0} → agent has data, but the signal
 *       is genuinely neutral. It DOES contribute (a zero weighted by its confidence).</li>
 * </ul>
 * This distinction fixes the 7/7-checklist pathology where a missing data source was
 * indistinguishable from a failed condition.
 *
 * <p><b>Veto</b>: when present, forces {@link DecisionType#NO_TRADE} regardless of the
 * score. Use sparingly — veto is for risk-level blockers (drawdown breach, maintenance
 * window), not for weak signals.
 */
public record AgentVote(
    String agentId,
    StrategyLayer layer,
    int directionalVote,
    double confidence,
    boolean abstain,
    List<String> evidence,
    Optional<String> vetoReason
) {
    public AgentVote {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId required");
        if (layer == null) throw new IllegalArgumentException("layer required");
        if (directionalVote < -100 || directionalVote > 100) {
            throw new IllegalArgumentException("directionalVote must be in [-100, 100], got " + directionalVote);
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0, 1], got " + confidence);
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (vetoReason == null) vetoReason = Optional.empty();
    }

    // ── Construction helpers ────────────────────────────────────────────────

    public static AgentVote abstain(String agentId, StrategyLayer layer, String why) {
        return new AgentVote(agentId, layer, 0, 0.0, true, List.of(why), Optional.empty());
    }

    public static AgentVote veto(String agentId, StrategyLayer layer, String reason) {
        return new AgentVote(agentId, layer, 0, 1.0, false, List.of(reason), Optional.of(reason));
    }

    public static AgentVote of(String agentId, StrategyLayer layer,
                                int directionalVote, double confidence,
                                List<String> evidence) {
        return new AgentVote(agentId, layer, directionalVote, confidence, false, evidence, Optional.empty());
    }

    public boolean hasVeto() {
        return vetoReason.isPresent();
    }
}
