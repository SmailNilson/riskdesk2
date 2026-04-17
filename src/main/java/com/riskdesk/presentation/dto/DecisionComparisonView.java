package com.riskdesk.presentation.dto;

import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;

import java.time.Instant;

/**
 * Side-by-side snapshot of the legacy playbook verdict and the new engine's
 * {@link StrategyDecision} for the same (instrument, timeframe) at the same
 * point in time. Used by the A/B validation workflow during the multi-slice
 * migration — consume it to count how often the two engines agree, disagree,
 * and on what direction / setup.
 *
 * <p>Both fields are nullable: either engine can return an empty result (e.g.
 * no setup detected or no playbook applicable), which is itself a valuable
 * comparison data point.
 */
public record DecisionComparisonView(
    String instrument,
    String timeframe,
    Instant evaluatedAt,
    PlaybookEvaluation legacyPlaybook,
    StrategyDecisionView strategyDecision,
    Agreement agreement
) {

    /**
     * Simple verdict comparison: did the two engines agree on the big-picture
     * decision (trade / don't trade) and direction? Kept deliberately coarse —
     * downstream analytics can inspect the full fields for nuance.
     */
    public enum Agreement {
        /** Both say NO_TRADE or equivalent — coarse agreement, no position. */
        BOTH_NO_TRADE,
        /** Both say tradeable AND same direction. */
        BOTH_TRADEABLE_SAME_DIRECTION,
        /** Both say tradeable but opposite directions — the worst case. */
        BOTH_TRADEABLE_OPPOSITE_DIRECTION,
        /** Legacy says trade, new engine says no. */
        LEGACY_ONLY_TRADEABLE,
        /** New engine says trade, legacy says no. */
        NEW_ONLY_TRADEABLE,
        /** One or both produced no conclusive output (insufficient data). */
        INCONCLUSIVE
    }

    public static DecisionComparisonView build(String instrument, String timeframe,
                                                PlaybookEvaluation legacy,
                                                StrategyDecision newDecision) {
        StrategyDecisionView newView = newDecision == null ? null : StrategyDecisionView.from(newDecision);
        return new DecisionComparisonView(
            instrument,
            timeframe,
            newDecision != null ? newDecision.evaluatedAt() : Instant.now(),
            legacy,
            newView,
            classify(legacy, newDecision)
        );
    }

    private static Agreement classify(PlaybookEvaluation legacy, StrategyDecision decision) {
        if (legacy == null || decision == null) return Agreement.INCONCLUSIVE;

        boolean legacyTradeable = legacyIsTradeable(legacy);
        boolean newTradeable = decision.decision().isTradeable()
            || decision.decision() == com.riskdesk.domain.engine.strategy.model.DecisionType.PAPER_TRADE;

        if (!legacyTradeable && !newTradeable) return Agreement.BOTH_NO_TRADE;
        if (legacyTradeable && !newTradeable) return Agreement.LEGACY_ONLY_TRADEABLE;
        if (!legacyTradeable) return Agreement.NEW_ONLY_TRADEABLE;

        // Both tradeable — compare directions.
        String legacyDir = legacy.filters() == null || legacy.filters().tradeDirection() == null
            ? null
            : legacy.filters().tradeDirection().name();
        String newDir = decision.direction().map(Enum::name).orElse(null);
        if (legacyDir == null || newDir == null) return Agreement.INCONCLUSIVE;
        return legacyDir.equals(newDir)
            ? Agreement.BOTH_TRADEABLE_SAME_DIRECTION
            : Agreement.BOTH_TRADEABLE_OPPOSITE_DIRECTION;
    }

    /**
     * A legacy playbook evaluation is considered "tradeable" if it produced a
     * best-setup with a checklist score ≥ 4 (the threshold where the legacy
     * engine historically displayed "WAIT for confirmation" rather than "NO TRADE").
     * Below 4 the legacy verdict text starts with "NO TRADE".
     */
    private static boolean legacyIsTradeable(PlaybookEvaluation legacy) {
        if (legacy == null) return false;
        if (legacy.bestSetup() == null) return false;
        return legacy.checklistScore() >= 4;
    }
}
