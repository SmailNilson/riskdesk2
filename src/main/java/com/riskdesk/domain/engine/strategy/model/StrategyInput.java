package com.riskdesk.domain.engine.strategy.model;

/**
 * Bundle passed to every {@link com.riskdesk.domain.engine.strategy.agent.StrategyAgent}
 * for a single evaluation. Pure value object — no mutability, no Spring.
 *
 * <p>{@code candidatePlaybookId} is set AFTER the
 * {@link com.riskdesk.domain.engine.strategy.playbook.PlaybookSelector} runs, so
 * zone/trigger agents can tune their evaluation to the selected setup (e.g. an
 * {@code OrderBlockZoneAgent} looks for bullish OBs when the candidate is a LONG
 * setup). Context agents run BEFORE selection and must ignore this field.
 */
public record StrategyInput(
    MarketContext context,
    ZoneContext zones,
    TriggerContext trigger,
    String candidatePlaybookId
) {
    public StrategyInput {
        if (context == null) throw new IllegalArgumentException("context required");
        if (zones == null) zones = ZoneContext.empty();
        if (trigger == null) trigger = TriggerContext.unavailable();
    }

    public StrategyInput withCandidatePlaybook(String id) {
        return new StrategyInput(context, zones, trigger, id);
    }
}
