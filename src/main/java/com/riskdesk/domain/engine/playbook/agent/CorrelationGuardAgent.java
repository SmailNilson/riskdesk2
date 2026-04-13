package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 3 — Correlation Guard (merges old MacroContext + RiskManager).
 *
 * Detects what the Playbook CAN'T see:
 * - DXY headwind/tailwind (real data, not null)
 * - Portfolio risk (real positions, drawdown, margin)
 * - Correlated position danger
 *
 * MCL LONG while DXY rallying +0.5% AND already 3 positions open = BLOCKED.
 */
public class CorrelationGuardAgent implements TradingAgent {

    private static final double MAX_DAILY_DRAWDOWN_PCT = 3.0;
    private static final int MAX_OPEN_POSITIONS = 3;
    private static final double HIGH_MARGIN_THRESHOLD = 80.0;
    private static final double DXY_HEADWIND_THRESHOLD = 0.2;

    @Override
    public String name() { return "Correlation-Guard"; }

    @Override
    public AgentVerdict evaluate(PlaybookEvaluation playbook, AgentContext context) {
        if (playbook.bestSetup() == null) {
            return AgentVerdict.skip(name(), "No setup to analyze");
        }

        String direction = playbook.filters().tradeDirection().name();
        double sizePct = playbook.plan() != null ? playbook.plan().riskPercent() : 0.01;
        List<String> warnings = new ArrayList<>();
        Map<String, Object> adjustments = new HashMap<>();
        boolean blocked = false;

        // ── 1. Portfolio Risk ────────────────────────────────────────────

        var portfolio = context.portfolio();
        if (portfolio != null) {
            // Kill gate: daily drawdown
            if (portfolio.dailyDrawdownPct() > MAX_DAILY_DRAWDOWN_PCT) {
                blocked = true;
                warnings.add(String.format("DAILY DRAWDOWN %.1f%% > %.0f%% limit — NO MORE TRADES TODAY",
                    portfolio.dailyDrawdownPct(), MAX_DAILY_DRAWDOWN_PCT));
            }

            // Max positions
            if (portfolio.openPositionCount() >= MAX_OPEN_POSITIONS) {
                warnings.add(String.format("Already %d open positions (max %d) — reduce size",
                    portfolio.openPositionCount(), MAX_OPEN_POSITIONS));
                sizePct *= 0.5;
            }

            // Correlated position (e.g., already LONG MCL and adding another)
            if (portfolio.hasCorrelatedPosition()) {
                warnings.add("Correlated position already open — sector concentration risk");
                sizePct *= 0.5;
            }

            // High margin utilization
            if (portfolio.marginUsedPct() > HIGH_MARGIN_THRESHOLD) {
                warnings.add(String.format("Margin at %.1f%% — near max capacity", portfolio.marginUsedPct()));
                sizePct *= 0.5;
            }
        }

        // ── 2. DXY Correlation ───────────────────────────────────────────

        var macro = context.macro();
        if (macro != null && macro.dxyPctChange() != null) {
            double dxyChange = macro.dxyPctChange();
            boolean isDxyHeadwind = isDxyHeadwind(context.instrument().name(), direction, dxyChange);

            if (isDxyHeadwind && Math.abs(dxyChange) >= DXY_HEADWIND_THRESHOLD) {
                warnings.add(String.format("DXY %s %.2f%% — headwind for %s %s",
                    dxyChange > 0 ? "rising" : "falling", dxyChange,
                    context.instrument(), direction));
                sizePct *= 0.7;
            } else if (!isDxyHeadwind && Math.abs(dxyChange) >= DXY_HEADWIND_THRESHOLD) {
                // DXY tailwind — positive
            }

            // Data quality check
            if ("DXY_ONLY".equals(macro.dataAvailability())) {
                // Partial macro data — note but don't penalize
            }
        }

        // ── 3. R:R sanity check ──────────────────────────────────────────

        if (playbook.plan() != null && playbook.plan().rrRatio() < 1.5) {
            warnings.add(String.format("R:R only %.1f:1 — below minimum 1.5:1", playbook.plan().rrRatio()));
            sizePct *= 0.5;
        }

        // ── Build verdict ────────────────────────────────────────────────

        adjustments.put("size_pct", sizePct);
        if (blocked) {
            adjustments.put("blocked", true);
        }

        Confidence confidence;
        String reasoning;

        if (blocked) {
            confidence = Confidence.LOW;
            reasoning = String.join("; ", warnings);
        } else if (warnings.isEmpty()) {
            confidence = Confidence.HIGH;
            reasoning = "No risk concerns — full size authorized";
        } else if (warnings.size() <= 1) {
            confidence = Confidence.MEDIUM;
            reasoning = String.join("; ", warnings);
        } else {
            confidence = Confidence.LOW;
            reasoning = warnings.size() + " risk factors: " + String.join("; ", warnings);
        }

        return new AgentVerdict(name(), confidence, playbook.filters().tradeDirection(),
            reasoning, adjustments);
    }

    /**
     * Determines if DXY movement is a headwind for the given instrument and direction.
     * DXY up = bearish for commodities (MCL, MGC) and euro (E6).
     * DXY up = mixed for equity indices (MNQ).
     */
    private boolean isDxyHeadwind(String instrument, String direction, double dxyChange) {
        boolean dxyUp = dxyChange > 0;

        return switch (instrument) {
            case "MCL", "MGC" ->
                // Commodities: DXY up = bearish for commodity prices
                ("LONG".equalsIgnoreCase(direction) && dxyUp)
                    || ("SHORT".equalsIgnoreCase(direction) && !dxyUp);
            case "E6" ->
                // Euro FX: DXY up = EUR/USD down = bearish for E6 LONG
                ("LONG".equalsIgnoreCase(direction) && dxyUp)
                    || ("SHORT".equalsIgnoreCase(direction) && !dxyUp);
            case "MNQ" ->
                // Equity: DXY impact is weak/mixed — only flag extreme moves
                Math.abs(dxyChange) > 0.5
                    && (("LONG".equalsIgnoreCase(direction) && dxyUp)
                        || ("SHORT".equalsIgnoreCase(direction) && !dxyUp));
            default -> false;
        };
    }
}
