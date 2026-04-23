package com.riskdesk.domain.engine.strategy.agent.context;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.PortfolioState;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;

/**
 * CONTEXT agent — the <b>only</b> mandatory safety gate in the engine.
 *
 * <p>Unlike the other agents, this one produces a directional vote of magnitude 0
 * on the happy path: when risk limits are respected, it says "no opinion — carry
 * on". Its real job is to emit a {@link AgentVote#veto(String, StrategyLayer, String)}
 * when any hard limit is breached, which the
 * {@link com.riskdesk.domain.engine.strategy.policy.StrategyScoringPolicy} turns
 * into a {@link com.riskdesk.domain.engine.strategy.model.DecisionType#NO_TRADE}.
 *
 * <p><b>Gates (in order)</b>
 * <ol>
 *   <li>Daily drawdown &gt; 3% → veto "daily-drawdown-breach"</li>
 *   <li>Margin utilisation &gt; 80% → veto "margin-near-limit"</li>
 *   <li>Open position count &ge; 3 AND has correlated position → veto
 *       "correlated-position-cap"</li>
 * </ol>
 *
 * <p>Thresholds are constants today; a future slice can move them to Spring
 * properties without changing the agent's contract. The thresholds chosen match
 * the legacy {@code RiskManagerAgent} defaults documented in CLAUDE.md.
 *
 * <p>When {@link PortfolioState#isKnown()} is false — typically because the
 * broker call failed — this agent <i>abstains</i> rather than vetoing. Refusing
 * to trade solely because portfolio data is momentarily unavailable would be a
 * reliability problem, not a safety one. Callers who want a stricter
 * fail-closed posture can compose a higher-level veto upstream.
 */
public final class RiskGateAgent implements StrategyAgent {

    public static final String ID = "risk-gate";

    // ── Thresholds ────────────────────────────────────────────────────────
    public static final double MAX_DAILY_DRAWDOWN_PCT = 3.0;
    public static final double MAX_MARGIN_UTILISATION_PCT = 80.0;
    public static final int MAX_OPEN_POSITIONS = 3;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public StrategyLayer layer() {
        return StrategyLayer.CONTEXT;
    }

    @Override
    public AgentVote evaluate(StrategyInput input) {
        PortfolioState p = input.context().portfolio();

        if (!p.isKnown()) {
            return AgentVote.abstain(ID, StrategyLayer.CONTEXT,
                "Portfolio state unavailable — no risk gate possible");
        }

        // 1. Daily drawdown
        if (p.dailyDrawdownPct() > MAX_DAILY_DRAWDOWN_PCT) {
            return AgentVote.veto(ID, StrategyLayer.CONTEXT,
                String.format("daily-drawdown-breach: %.2f%% > %.1f%% limit",
                    p.dailyDrawdownPct(), MAX_DAILY_DRAWDOWN_PCT));
        }

        // 2. Margin utilisation
        if (p.marginUsedPct() > MAX_MARGIN_UTILISATION_PCT) {
            return AgentVote.veto(ID, StrategyLayer.CONTEXT,
                String.format("margin-near-limit: %.1f%% > %.0f%% cap",
                    p.marginUsedPct(), MAX_MARGIN_UTILISATION_PCT));
        }

        // 3. Correlated exposure cap
        if (p.openPositionCount() >= MAX_OPEN_POSITIONS && p.hasCorrelatedPosition()) {
            return AgentVote.veto(ID, StrategyLayer.CONTEXT,
                "correlated-position-cap: " + p.openPositionCount()
                    + " open positions, correlated exposure present");
        }

        // Happy path: no opinion on direction, but we actively report so the
        // operator sees the gate is alive. Zero-vote with reasonable confidence —
        // counts in the denominator but doesn't shift the score.
        return AgentVote.of(ID, StrategyLayer.CONTEXT, 0, 0.30,
            java.util.List.of("Risk gates ok",
                String.format("dd=%.2f%% margin=%.1f%% positions=%d",
                    p.dailyDrawdownPct(), p.marginUsedPct(), p.openPositionCount())));
    }
}
