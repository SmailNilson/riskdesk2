package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;

import java.util.Map;

/**
 * Agent 2: Evaluates risk based on portfolio state, position correlation,
 * ATR volatility, and daily drawdown. Pure mechanical rules — no AI call.
 */
public class RiskManagerAgent implements TradingAgent {

    private static final double MAX_RISK_PCT = 0.01;    // 1% base
    private static final double MAX_DRAWDOWN = 0.03;     // 3% daily stop
    private static final double HIGH_VOL_MULTIPLIER = 1.5;

    @Override
    public String name() { return "RiskManager"; }

    @Override
    public AgentVerdict evaluate(PlaybookEvaluation playbook, AgentContext context) {
        if (playbook.plan() == null) {
            return AgentVerdict.skip(name(), "No trade plan to assess");
        }

        double sizePct = MAX_RISK_PCT;
        var portfolio = context.portfolio();
        var filters = playbook.filters();

        // Rule 1: Structure quality reduces size
        sizePct *= filters.sizeMultiplier();

        // Rule 2: Correlated position reduces size
        if (portfolio.hasCorrelatedPosition()) {
            sizePct *= 0.5;
        }

        // Rule 3: Daily drawdown gate
        if (portfolio.dailyDrawdownPct() > MAX_DRAWDOWN) {
            return new AgentVerdict(name(), Confidence.LOW, Direction.LONG,
                String.format("Daily drawdown %.1f%% > 3%% limit — STOP trading",
                    portfolio.dailyDrawdownPct() * 100),
                Map.of("size_pct", 0.0, "blocked", true));
        }

        // Rule 4: High volatility
        // ATR comparison would need historical average; use plan R:R as proxy
        if (playbook.plan().rrRatio() < 1.5) {
            sizePct *= 0.5;
        }

        // Rule 5: Max open positions
        if (portfolio.openPositionCount() >= 3) {
            sizePct *= 0.5;
        }

        Confidence conf = sizePct >= 0.008 ? Confidence.HIGH
                        : sizePct >= 0.004 ? Confidence.MEDIUM
                        : Confidence.LOW;

        String reasoning = String.format("Risk: %.2f%%, %s correlated position, %d open positions, drawdown %.1f%%",
            sizePct * 100,
            portfolio.hasCorrelatedPosition() ? "with" : "no",
            portfolio.openPositionCount(),
            portfolio.dailyDrawdownPct() * 100);

        return new AgentVerdict(name(), conf, filters.tradeDirection(), reasoning,
            Map.of("size_pct", sizePct));
    }
}
