package com.riskdesk.domain.engine.strategy.agent.context;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.SessionInfo;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;

import java.util.ArrayList;
import java.util.List;

/**
 * CONTEXT agent — enforces session / liquidity discipline.
 *
 * <p>Does NOT vote on direction. Like {@link RiskGateAgent} it either
 * abstains (normal operation) or emits hard vetoes when the market environment
 * is structurally unsuitable for trading.
 *
 * <p><b>Decisions</b>
 * <ul>
 *   <li>Session data unavailable → abstain (reliability over safety, same as
 *       {@link RiskGateAgent})</li>
 *   <li>Maintenance window active → veto "maintenance-window"</li>
 *   <li>Market closed for this instrument → veto "market-closed"</li>
 *   <li>Reference timeframe {@code 5m} AND not in a kill zone → veto
 *       "5m-outside-kill-zone". Rationale: 5m setups in low-liquidity chop are
 *       the single biggest source of bad trades in the legacy data; the
 *       {@code SignalConfluenceBuffer} was originally designed around this
 *       exact gate</li>
 *   <li>Low liquidity (Asian session / CLOSE phase) → small negative
 *       directional vote (magnitude 10) with low confidence. Doesn't block the
 *       setup, just makes conviction harder to accumulate</li>
 *   <li>Otherwise → zero-vote abstain-adjacent (active evidence of "all clear")</li>
 * </ul>
 */
public final class SessionTimingAgent implements StrategyAgent {

    public static final String ID = "session-timing";

    /** Timeframes that require an active kill zone (ICT session windows). */
    private static final String KILL_ZONE_SENSITIVE_TIMEFRAME = "5m";

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
        SessionInfo s = input.context().session();

        if (!s.isKnown()) {
            return AgentVote.abstain(ID, StrategyLayer.CONTEXT,
                "Session info unavailable");
        }

        // Maintenance window — CME daily maintenance is 16:00–18:00 ET
        if (s.maintenanceWindow()) {
            return AgentVote.veto(ID, StrategyLayer.CONTEXT,
                "maintenance-window: daily maintenance active, liquidity thin/dark");
        }

        // Market closed for the instrument
        if (!s.marketOpen()) {
            return AgentVote.veto(ID, StrategyLayer.CONTEXT,
                "market-closed: instrument not currently tradeable");
        }

        // 5m kill-zone — DEMOTED from veto to informational signal (per operator
        // request 2026-04-30). NY regular session 09:30–16:00 ET produces tradable
        // 5m setups well past the original kill-zone window (08:30–11:00 ET), so
        // a hard veto over-restricts the user. The signal still appears in
        // evidence so downstream consumers can see the timing context, but the
        // setup is no longer blocked. The legacy ConfluenceBuffer keeps its own
        // gate independently for backtest-driven setups.
        String tf = input.context().referenceTimeframe();
        if (KILL_ZONE_SENSITIVE_TIMEFRAME.equalsIgnoreCase(tf) && !s.killZone()) {
            List<String> evidence = new ArrayList<>();
            evidence.add("5m setup outside London/NY kill zones — informational only");
            evidence.add("Original window: London 02:00–05:00 ET or NY 08:30–11:00 ET");
            return AgentVote.of(ID, StrategyLayer.CONTEXT, 0, 0.20, evidence);
        }

        // Low-liquidity (Asian / CLOSE) — discourage without blocking
        if (s.isLowLiquidity()) {
            List<String> evidence = new ArrayList<>();
            evidence.add("Low-liquidity session (" + (s.phase() == null ? "unknown" : s.phase()) + ")");
            evidence.add("Discouraging conviction accumulation");
            return AgentVote.of(ID, StrategyLayer.CONTEXT, -10, 0.35, evidence);
        }

        // All-clear
        List<String> evidence = new ArrayList<>();
        evidence.add("Session " + (s.phase() == null ? "unknown" : s.phase()) + " — all clear");
        if (s.isHighLiquidity()) {
            evidence.add("High-liquidity window");
        }
        return AgentVote.of(ID, StrategyLayer.CONTEXT, 0, 0.25, evidence);
    }
}
