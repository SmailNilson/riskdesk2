package com.riskdesk.domain.quant.structure;

import java.util.List;

/**
 * Minimal projection of {@code /api/strategy/{instr}/5m} for the
 * {@link StructuralFilterEvaluator}. Carries the agent votes and the final
 * decision/veto reasons — enough to detect CHOPPY regime and Java-side
 * NO_TRADE blockers.
 *
 * @param decision     decision label (e.g. {@code "TRADE"}, {@code "NO_TRADE"})
 * @param votes        per-agent votes
 * @param vetoReasons  flat list of veto reasons surfaced by any agent
 */
public record StrategyVotes(
    String decision,
    List<Vote> votes,
    List<String> vetoReasons
) {
    public StrategyVotes {
        votes = votes == null ? List.of() : List.copyOf(votes);
        vetoReasons = vetoReasons == null ? List.of() : List.copyOf(vetoReasons);
    }

    /** A single agent vote — only the dimensions used by the evaluator. */
    public record Vote(String agentId, List<String> evidence) {
        public Vote {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }
}
