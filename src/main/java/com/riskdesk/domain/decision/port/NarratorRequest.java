package com.riskdesk.domain.decision.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * Input to {@link DecisionNarratorPort}. Carries everything the narrator needs to produce
 * a human-readable paragraph without re-running analysis: the verdict (authoritative),
 * the plan, and each agent's reasoning line.
 *
 * <p>Intentionally <b>does not</b> carry raw market data — the narrator must not second-guess
 * the decision. It only has the already-computed verdicts to rephrase in natural language.
 */
public record NarratorRequest(
    String instrument,
    String timeframe,
    String direction,
    String setupType,
    String zoneName,
    String eligibility,
    double sizePercent,
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    Double rrRatio,
    List<AgentVerdictLine> agentVerdicts,
    List<String> warnings,
    /** Preferred narration language, e.g. {@code "fr"} or {@code "en"}. */
    String language
) {

    /** One agent's verdict reduced to a single line for the narrator. */
    public record AgentVerdictLine(
        String agentName,
        String confidence,
        String reasoning
    ) {}
}
