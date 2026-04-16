package com.riskdesk.application.service;

import com.riskdesk.domain.engine.playbook.agent.AgentContext;
import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.playbook.model.RiskFraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Portfolio / risk gate evaluated BEFORE AI agents are invoked.
 *
 * <p>Extracts the mechanical rules formerly baked into {@code CorrelationGuardAgent}:
 * daily drawdown kill switch, max open positions, correlated-position penalty,
 * margin utilization cap, DXY headwind per instrument, and minimum R:R.
 *
 * <p>This runs deterministically in ~microseconds. If it returns {@code blocked=true},
 * the orchestrator short-circuits and skips all Gemini calls — saves cost and
 * guarantees the kill switch works even if the LLM is down.
 *
 * <p>Otherwise it returns a size-percentage cap that AI agents may reduce further
 * but never increase above.
 */
@Service
public class RiskManagementService {

    private static final Logger log = LoggerFactory.getLogger(RiskManagementService.class);

    // Kill switches
    private static final double MAX_DAILY_DRAWDOWN_PCT = 3.0;
    private static final int MAX_OPEN_POSITIONS = 3;
    private static final double HIGH_MARGIN_THRESHOLD = 80.0;
    private static final double MIN_RR_RATIO = 1.5;

    // DXY headwind threshold (pct change) — below which DXY is considered neutral
    private static final double DXY_HEADWIND_THRESHOLD = 0.2;

    public RiskGateVerdict evaluate(PlaybookEvaluation playbook, AgentContext context) {
        if (playbook.plan() == null) {
            return RiskGateVerdict.eligible(0, List.of("No plan — gate skipped"));
        }

        String instrument = context.instrument() != null ? context.instrument().name() : "UNKNOWN";
        Direction direction = playbook.filters().tradeDirection();
        double baseRisk = playbook.plan().riskPercent();
        double sizePct = baseRisk;
        List<String> warnings = new ArrayList<>();
        boolean blocked = false;
        String blockReason = null;

        var portfolio = context.portfolio();
        if (portfolio != null) {
            // 1. Daily drawdown kill switch — absolute block
            if (portfolio.dailyDrawdownPct() > MAX_DAILY_DRAWDOWN_PCT) {
                blocked = true;
                blockReason = String.format("DAILY DRAWDOWN %.1f%% > %.0f%% — NO MORE TRADES TODAY",
                    portfolio.dailyDrawdownPct(), MAX_DAILY_DRAWDOWN_PCT);
                warnings.add(blockReason);
            }

            // 2. Max open positions — halve size
            if (!blocked && portfolio.openPositionCount() >= MAX_OPEN_POSITIONS) {
                warnings.add(String.format("Already %d open positions (max %d) — half size",
                    portfolio.openPositionCount(), MAX_OPEN_POSITIONS));
                sizePct *= 0.5;
            }

            // 3. Correlated position — halve size
            if (!blocked && portfolio.hasCorrelatedPosition()) {
                warnings.add("Correlated position already open — half size");
                sizePct *= 0.5;
            }

            // 4. Margin utilization — halve size near capacity
            if (!blocked && portfolio.marginUsedPct() > HIGH_MARGIN_THRESHOLD) {
                warnings.add(String.format("Margin %.1f%% — near max capacity, half size",
                    portfolio.marginUsedPct()));
                sizePct *= 0.5;
            }
        }

        // 5. DXY headwind — 30% size cut if macro wind is against us
        if (!blocked && context.macro() != null && context.macro().dxyPctChange() != null) {
            double dxyChange = context.macro().dxyPctChange();
            if (isDxyHeadwind(instrument, direction, dxyChange)
                    && Math.abs(dxyChange) >= DXY_HEADWIND_THRESHOLD) {
                warnings.add(String.format("DXY %s %.2f%% — headwind for %s %s, size -30%%",
                    dxyChange > 0 ? "rising" : "falling", dxyChange, instrument, direction));
                sizePct *= 0.7;
            }
        }

        // 6. R:R sanity — never below 1.5
        if (!blocked && playbook.plan().rrRatio() < MIN_RR_RATIO) {
            warnings.add(String.format("R:R %.1f:1 below min %.1f:1 — half size",
                playbook.plan().rrRatio(), MIN_RR_RATIO));
            sizePct *= 0.5;
        }

        if (blocked) {
            log.info("Risk gate BLOCKED {} {}: {}", instrument, direction, blockReason);
            return RiskGateVerdict.blocked(blockReason, warnings);
        }

        // Clamp the cumulative reductions back into RiskFraction's contract so downstream
        // consumers can rely on the value being a valid risk fraction, not e.g. a
        // floating-point drift that bleeds into PlaybookPlan.withAdjustedSize().
        sizePct = RiskFraction.clamp(sizePct);

        log.debug("Risk gate passed {} {}: size {}% (base {}%), {} warnings",
            instrument, direction,
            String.format("%.4f", RiskFraction.toPercent(sizePct)),
            String.format("%.4f", RiskFraction.toPercent(baseRisk)),
            warnings.size());
        return RiskGateVerdict.eligible(sizePct, warnings);
    }

    /**
     * DXY headwind rule per asset class.
     * <ul>
     *   <li>MCL/MGC (commodities): DXY up = bearish for LONG / bullish for SHORT</li>
     *   <li>E6 (Euro FX): DXY up = bearish for LONG Euro / bullish for SHORT</li>
     *   <li>MNQ (equity): only extreme DXY moves (>0.5%) matter</li>
     *   <li>Other: no rule</li>
     * </ul>
     */
    private static boolean isDxyHeadwind(String instrument, Direction direction, double dxyChange) {
        boolean dxyUp = dxyChange > 0;
        boolean isLong = direction == Direction.LONG;

        return switch (instrument) {
            case "MCL", "MGC", "E6" -> (isLong && dxyUp) || (!isLong && !dxyUp);
            case "MNQ" -> Math.abs(dxyChange) > 0.5
                          && ((isLong && dxyUp) || (!isLong && !dxyUp));
            default -> false;
        };
    }

    // ── Verdict record ────────────────────────────────────────────────────

    public record RiskGateVerdict(
        boolean blocked,
        double sizePct,
        List<String> warnings,
        String blockReason
    ) {
        public static RiskGateVerdict eligible(double sizePct, List<String> warnings) {
            return new RiskGateVerdict(false, sizePct, List.copyOf(warnings), null);
        }

        public static RiskGateVerdict blocked(String reason, List<String> warnings) {
            return new RiskGateVerdict(true, 0, List.copyOf(warnings), reason);
        }
    }
}
