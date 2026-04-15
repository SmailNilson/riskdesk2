package com.riskdesk.domain.decision.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A trade decision produced by the agent orchestrator and narrated for human operators.
 *
 * <p>The agent orchestrator is the <b>authoritative decider</b> — this record captures its
 * verdict as-is (eligibility, size, plan). The narrator adds a natural-language paragraph
 * for UI display but never changes the verdict.
 *
 * <p>This is the decision-side counterpart to {@code MentorSignalReviewRecord}. Introduced
 * as part of the decoupling: agents decide, this record persists + narrates + triggers
 * execution. No re-decision is done downstream.
 *
 * @param id                    generated DB id, {@code null} before first save
 * @param revision              revision number, 1 for first decision, 2+ for re-analyses
 * @param createdAt             when the decision was captured
 * @param instrument            e.g. {@code "MCL"}, {@code "MGC"}
 * @param timeframe             e.g. {@code "10m"}, {@code "1h"}
 * @param direction             {@code "LONG"} / {@code "SHORT"} / {@code "FLAT"}
 * @param setupType             e.g. {@code "ZONE_RETEST"}, {@code "LIQUIDITY_SWEEP"}
 * @param zoneName              human-readable zone label, e.g. {@code "OB BULLISH 91.03-94.71"}
 * @param eligibility           {@code "ELIGIBLE"} / {@code "INELIGIBLE"} / {@code "BLOCKED"}
 * @param sizePercent           final size multiplier from orchestrator, already gated
 * @param verdict               one-line summary (e.g. {@code "LONG — ZONE RETEST — 6/7 — eligible"})
 * @param agentVerdictsJson     serialized {@code List<AgentVerdict>} for audit
 * @param warningsJson          serialized list of warnings from the orchestrator
 * @param entryPrice            from {@code PlaybookPlan.entryPrice()}
 * @param stopLoss              from {@code PlaybookPlan.stopLoss()}
 * @param takeProfit1           from {@code PlaybookPlan.takeProfit1()}
 * @param takeProfit2           from {@code PlaybookPlan.takeProfit2()}
 * @param rrRatio               risk-reward ratio from the plan
 * @param narrative             Gemini-generated narrative paragraph (French), or null if narration failed
 * @param narrativeModel        Gemini model used (e.g. {@code "gemini-3.1-pro-preview"}), null if not called
 * @param narrativeLatencyMs    how long the narration call took, null if not called
 * @param status                {@code "NARRATING"} / {@code "DONE"} / {@code "ERROR"}
 * @param errorMessage          populated only when {@code status == "ERROR"}
 */
public record TradeDecision(
    Long id,
    int revision,
    Instant createdAt,

    String instrument,
    String timeframe,
    String direction,
    String setupType,
    String zoneName,

    String eligibility,
    double sizePercent,
    String verdict,
    String agentVerdictsJson,
    String warningsJson,

    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    Double rrRatio,

    String narrative,
    String narrativeModel,
    Long narrativeLatencyMs,

    String status,
    String errorMessage
) {

    public static final String STATUS_NARRATING = "NARRATING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_ERROR = "ERROR";

    /** Returns a copy with the given id set — used after first JPA save. */
    public TradeDecision withId(Long id) {
        return new TradeDecision(
            id, revision, createdAt,
            instrument, timeframe, direction, setupType, zoneName,
            eligibility, sizePercent, verdict, agentVerdictsJson, warningsJson,
            entryPrice, stopLoss, takeProfit1, takeProfit2, rrRatio,
            narrative, narrativeModel, narrativeLatencyMs,
            status, errorMessage
        );
    }

    /** Returns a copy with the narration results filled in — used after GeminiNarrator completes. */
    public TradeDecision withNarration(String narrative, String model, long latencyMs) {
        return new TradeDecision(
            id, revision, createdAt,
            instrument, timeframe, direction, setupType, zoneName,
            eligibility, sizePercent, verdict, agentVerdictsJson, warningsJson,
            entryPrice, stopLoss, takeProfit1, takeProfit2, rrRatio,
            narrative, model, latencyMs,
            STATUS_DONE, null
        );
    }

    /** Returns a copy marked as errored — used when narration fails. */
    public TradeDecision withError(String errorMessage) {
        return new TradeDecision(
            id, revision, createdAt,
            instrument, timeframe, direction, setupType, zoneName,
            eligibility, sizePercent, verdict, agentVerdictsJson, warningsJson,
            entryPrice, stopLoss, takeProfit1, takeProfit2, rrRatio,
            narrative, narrativeModel, narrativeLatencyMs,
            STATUS_ERROR, errorMessage
        );
    }
}
