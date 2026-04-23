package com.riskdesk.domain.engine.strategy.model;

/**
 * The three strict layers of the top-down decision funnel.
 *
 * <ul>
 *   <li>{@link #CONTEXT} — the "where" and "why" (slow data: HTF SMC, Volume Profile, Regime, PD array).
 *       Produces facts about the market environment, NEVER a trigger.</li>
 *   <li>{@link #ZONE} — the "what" (medium data: Order Blocks, FVG, Liquidity levels, HVN/LVN nodes).
 *       Identifies where a trade could fire. Never a signal on its own.</li>
 *   <li>{@link #TRIGGER} — the "when" (ultra-fast data: Footprint delta, Lee-Ready flow, DOM, reaction).
 *       Validates entry at the millisecond. Never produces bias or zones.</li>
 * </ul>
 *
 * <p><b>Invariant:</b> an agent MUST declare a single layer. The orchestrator aggregates votes
 * per layer separately before collapsing to a final score. No cross-layer voting.
 */
public enum StrategyLayer {
    CONTEXT,
    ZONE,
    TRIGGER
}
