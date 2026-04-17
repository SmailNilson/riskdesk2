package com.riskdesk.domain.engine.strategy.model;

import com.riskdesk.domain.engine.playbook.model.Direction;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The full decision emitted by the StrategyEngine for one (instrument, timeframe)
 * evaluation. This is the single source of truth consumed by the presentation layer
 * and — in a later slice — by {@code ExecutionManagerService}.
 *
 * <p><b>Read order for the execution layer:</b>
 * <ol>
 *   <li>{@code vetoReasons} → any non-empty list forces {@link DecisionType#NO_TRADE}.</li>
 *   <li>{@code decision} → the authoritative sizing bucket.</li>
 *   <li>{@code plan} → entry/SL/TP. Always present when decision is tradeable.</li>
 * </ol>
 * Never re-derive the decision from {@code finalScore} — the policy layer owns that
 * mapping and may refine it per instrument.
 */
public record StrategyDecision(
    Optional<String> candidatePlaybookId,
    List<AgentVote> votes,
    Map<StrategyLayer, Double> layerScores,
    double finalScore,
    DecisionType decision,
    Optional<Direction> direction,
    Optional<MechanicalPlan> plan,
    List<String> vetoReasons,
    Instant evaluatedAt
) {
    public StrategyDecision {
        if (candidatePlaybookId == null) candidatePlaybookId = Optional.empty();
        votes = votes == null ? List.of() : List.copyOf(votes);
        layerScores = layerScores == null ? Map.of() : Map.copyOf(layerScores);
        if (decision == null) decision = DecisionType.NO_TRADE;
        if (direction == null) direction = Optional.empty();
        if (plan == null) plan = Optional.empty();
        vetoReasons = vetoReasons == null ? List.of() : List.copyOf(vetoReasons);
        if (evaluatedAt == null) evaluatedAt = Instant.now();
    }

    /** No playbook was applicable — early exit from the engine. */
    public static StrategyDecision standby(Instant at, List<AgentVote> contextVotes,
                                           Map<StrategyLayer, Double> layerScores) {
        return new StrategyDecision(
            Optional.empty(), contextVotes, layerScores, 0.0,
            DecisionType.NO_TRADE, Optional.empty(), Optional.empty(),
            List.of(), at
        );
    }
}
