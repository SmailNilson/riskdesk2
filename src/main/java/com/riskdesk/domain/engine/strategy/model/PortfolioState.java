package com.riskdesk.domain.engine.strategy.model;

/**
 * Minimal portfolio picture consumed by risk-gating CONTEXT agents.
 *
 * <p>Deliberately a strategy-local type — we do NOT reuse
 * {@code com.riskdesk.domain.engine.playbook.agent.AgentContext.PortfolioState}
 * because that couples the new engine to the legacy agent package. The strategy
 * module stays self-contained; the application-layer builder is the single place
 * that knows about both representations.
 *
 * <p>{@link #unknown()} signals "no portfolio data available" — agents MUST check
 * {@link #isKnown()} and abstain rather than treating zeros as "no risk".
 *
 * @param dailyDrawdownPct percentage (0..100), i.e. 3.0 = 3% drawdown
 * @param marginUsedPct    percentage (0..100)
 */
public record PortfolioState(
    double totalUnrealizedPnL,
    double dailyDrawdownPct,
    int openPositionCount,
    boolean hasCorrelatedPosition,
    double marginUsedPct,
    boolean known
) {
    public PortfolioState {
        if (dailyDrawdownPct < 0) dailyDrawdownPct = 0;
        if (marginUsedPct < 0) marginUsedPct = 0;
    }

    /**
     * Back-compat 5-arg constructor marking the state as known. Tests and callers
     * that have real data use this; the 6-arg canonical form reserves {@code known=false}
     * for the {@link #unknown()} sentinel.
     */
    public PortfolioState(double totalUnrealizedPnL, double dailyDrawdownPct,
                           int openPositionCount, boolean hasCorrelatedPosition,
                           double marginUsedPct) {
        this(totalUnrealizedPnL, dailyDrawdownPct, openPositionCount,
            hasCorrelatedPosition, marginUsedPct, true);
    }

    public static PortfolioState unknown() {
        return new PortfolioState(0.0, 0.0, 0, false, 0.0, false);
    }

    public boolean isKnown() {
        return known;
    }
}
