package com.riskdesk.domain.notification.event;

import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when a Mentor AI review completes with execution eligibility ELIGIBLE
 * and a complete trade plan (Entry / SL / TP).
 *
 * <p><b>S3b/S4 enrichment (optional):</b> the four {@code strategy*} fields carry
 * the concurrent verdict of the probabilistic strategy engine, surfaced in the
 * Telegram notification so operators see both engines side-by-side. Fields are
 * nullable because the strategy engine is an optional collaborator; callers that
 * don't have it wired simply leave them null and the notification renders as
 * before. A back-compat constructor preserves existing test / publisher call
 * sites.
 */
public record TradeValidatedEvent(
        String instrument,
        String action,
        String timeframe,
        String verdict,
        String technicalQuickAnalysis,
        Double entryPrice,
        Double deepEntryPrice,
        Double stopLoss,
        Double takeProfit,
        Double rewardToRiskRatio,
        Instant timestamp,
        /** Strategy engine candidate playbook id (e.g. "NOR", "SBDR"). Nullable. */
        String strategyPlaybookId,
        /** Strategy engine decision bucket name ("HALF_SIZE", "PAPER_TRADE", ...). Nullable. */
        String strategyDecision,
        /** Strategy engine final score on [-100, +100]. Nullable. */
        Double strategyFinalScore,
        /**
         * Whether the engine's verdict matches the Mentor review's action+eligibility:
         *  {@code true}  — engine says tradeable AND direction matches review action
         *  {@code false} — engine disagrees (decision not tradeable OR direction mismatch)
         *  {@code null}  — engine did not evaluate (not wired, errored, skipped)
         */
        Boolean strategyAgreesWithReview
) implements DomainEvent {

    /**
     * Back-compat constructor for call sites that don't supply strategy-engine
     * context. Keeps every pre-S3b caller (tests, legacy code) compiling untouched.
     */
    public TradeValidatedEvent(String instrument, String action, String timeframe,
                                String verdict, String technicalQuickAnalysis,
                                Double entryPrice, Double deepEntryPrice,
                                Double stopLoss, Double takeProfit,
                                Double rewardToRiskRatio, Instant timestamp) {
        this(instrument, action, timeframe, verdict, technicalQuickAnalysis,
            entryPrice, deepEntryPrice, stopLoss, takeProfit, rewardToRiskRatio,
            timestamp, null, null, null, null);
    }
}
