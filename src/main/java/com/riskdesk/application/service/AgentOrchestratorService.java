package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.application.dto.MacroCorrelationSnapshot;
import com.riskdesk.application.dto.PortfolioSummary;
import com.riskdesk.domain.engine.playbook.agent.*;
import com.riskdesk.domain.engine.playbook.model.FinalVerdict;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.playbook.model.PlaybookInput;
import com.riskdesk.domain.engine.playbook.model.PlaybookPlan;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.TradingSessionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class AgentOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorService.class);
    private static final Duration AGENT_TIMEOUT = Duration.ofSeconds(5);

    private final List<TradingAgent> agents;
    private final PositionService positionService;
    private final MentorIntermarketService intermarketService;
    private final IndicatorService indicatorService;

    public AgentOrchestratorService(
            PositionService positionService,
            MentorIntermarketService intermarketService,
            IndicatorService indicatorService) {
        this.positionService = positionService;
        this.intermarketService = intermarketService;
        this.indicatorService = indicatorService;

        // 5 specialized domain agents — pure Java, no Spring injection
        this.agents = List.of(
            new MtfConfluenceAgent(),
            new DivergenceHunterAgent(),
            new CorrelationGuardAgent(),
            new SessionTimingAgent(),
            new ZoneQualityAgent()
        );
    }

    public FinalVerdict orchestrate(PlaybookEvaluation playbook, AgentContext context) {
        if (playbook.bestSetup() == null || playbook.plan() == null) {
            return new FinalVerdict(
                playbook.verdict(), null, 0, List.of(),
                List.of("No setup detected"), "INELIGIBLE"
            );
        }

        // Run all agents in parallel
        List<CompletableFuture<AgentVerdict>> futures = agents.stream()
            .map(agent -> CompletableFuture.supplyAsync(() -> {
                try {
                    return agent.evaluate(playbook, context);
                } catch (Exception e) {
                    log.warn("Agent {} failed: {}", agent.name(), e.getMessage());
                    return AgentVerdict.timeout(agent.name());
                }
            }))
            .toList();

        // Collect results with timeout
        List<AgentVerdict> verdicts = new ArrayList<>();
        for (CompletableFuture<AgentVerdict> future : futures) {
            try {
                verdicts.add(future.get(AGENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                verdicts.add(AgentVerdict.timeout("unknown"));
            }
        }

        // Resolve conflicts and build final verdict
        return resolveVerdicts(playbook, verdicts);
    }

    private FinalVerdict resolveVerdicts(PlaybookEvaluation playbook, List<AgentVerdict> verdicts) {
        List<String> warnings = new ArrayList<>();
        double sizePct = playbook.plan().riskPercent();
        boolean blocked = false;

        for (AgentVerdict v : verdicts) {
            // Apply size adjustments
            if (v.adjustments().containsKey("size_pct")) {
                double agentSize = ((Number) v.adjustments().get("size_pct")).doubleValue();
                sizePct = Math.min(sizePct, agentSize);
            }

            // Check for blocks
            if (v.adjustments().containsKey("blocked") && (boolean) v.adjustments().get("blocked")) {
                blocked = true;
                warnings.add(v.agentName() + ": " + v.reasoning());
            }

            // Collect low-confidence warnings
            if (v.confidence() == com.riskdesk.domain.engine.playbook.model.Confidence.LOW
                    && !v.adjustments().containsKey("blocked")) {
                warnings.add(v.agentName() + ": " + v.reasoning());
            }
        }

        String eligibility = blocked ? "BLOCKED" : (sizePct > 0 ? "ELIGIBLE" : "INELIGIBLE");

        PlaybookPlan adjustedPlan = blocked ? null : playbook.plan().withAdjustedSize(sizePct);

        String verdict;
        if (blocked) {
            verdict = "BLOCKED — " + warnings.get(0);
        } else if (warnings.isEmpty()) {
            verdict = playbook.verdict() + " — ALL AGENTS ALIGNED";
        } else {
            verdict = playbook.verdict() + " — " + warnings.size() + " warning(s)";
        }

        log.info("Orchestrator verdict: {} (size: {}%, agents: {}, warnings: {})",
            verdict, String.format("%.4f", sizePct * 100), verdicts.size(), warnings.size());

        return new FinalVerdict(verdict, adjustedPlan, sizePct, verdicts, warnings, eligibility);
    }

    /**
     * Builds a rich AgentContext from real application services.
     * This is the ONLY place where application-layer data is mapped
     * to domain-level agent records.
     */
    public AgentContext buildContext(Instrument instrument, String timeframe,
                                     IndicatorSnapshot snapshot, BigDecimal atr) {
        // 1. PlaybookInput (current TF)
        PlaybookInput input = PlaybookService.toPlaybookInput(snapshot, atr != null ? atr : BigDecimal.ONE);

        // 2. Real portfolio state
        AgentContext.PortfolioState portfolio = buildPortfolioState(instrument);

        // 3. Real macro/DXY context
        AgentContext.MacroSnapshot macro = buildMacroSnapshot(instrument);

        // 4. Multi-timeframe bias
        AgentContext.MtfSnapshot mtf = buildMtfSnapshot(instrument);

        // 5. Momentum indicators
        AgentContext.MomentumSnapshot momentum = buildMomentumSnapshot(snapshot);

        // 6. Session timing
        AgentContext.SessionInfo session = buildSessionInfo(instrument);

        return new AgentContext(instrument, timeframe, input, portfolio, macro, mtf, momentum, session, atr);
    }

    // ── Private builders ─────────────────────────────────────────────────

    private AgentContext.PortfolioState buildPortfolioState(Instrument instrument) {
        try {
            PortfolioSummary summary = positionService.getPortfolioSummary();
            if (summary == null) return AgentContext.PortfolioState.empty();

            boolean correlated = summary.openPositions() != null
                && summary.openPositions().stream()
                    .anyMatch(p -> p.instrument().equals(instrument.name()) && p.open());

            double marginPct = summary.marginUsedPct() != null
                ? summary.marginUsedPct().doubleValue() : 0;

            // Daily drawdown: simplified as unrealized loss % of total exposure
            double drawdown = 0;
            if (summary.totalUnrealizedPnL() != null && summary.totalUnrealizedPnL().doubleValue() < 0
                    && summary.totalExposure() != null && summary.totalExposure().doubleValue() > 0) {
                drawdown = Math.abs(summary.totalUnrealizedPnL().doubleValue()
                    / summary.totalExposure().doubleValue()) * 100;
            }

            return new AgentContext.PortfolioState(
                summary.totalUnrealizedPnL() != null ? summary.totalUnrealizedPnL().doubleValue() : 0,
                drawdown,
                (int) summary.openPositionCount(),
                correlated,
                marginPct
            );
        } catch (Exception e) {
            log.debug("Failed to build portfolio state: {}", e.getMessage());
            return AgentContext.PortfolioState.empty();
        }
    }

    private AgentContext.MacroSnapshot buildMacroSnapshot(Instrument instrument) {
        try {
            MacroCorrelationSnapshot macro = intermarketService.currentForAssetClass(
                instrument, instrument.assetClass());

            String sessionPhase = null;
            boolean killZone = false;
            try {
                var phase = TradingSessionResolver.currentPhase();
                sessionPhase = phase != null ? phase.name() : null;
                killZone = TradingSessionResolver.isWithinKillZone(Instant.now());
            } catch (Exception ignored) {}

            return new AgentContext.MacroSnapshot(
                macro.dxyPctChange(),
                macro.dxyTrend(),
                macro.correlationAlignment(),
                macro.dataAvailability(),
                sessionPhase,
                killZone
            );
        } catch (Exception e) {
            log.debug("Failed to build macro snapshot: {}", e.getMessage());
            return AgentContext.MacroSnapshot.empty();
        }
    }

    private AgentContext.MtfSnapshot buildMtfSnapshot(Instrument instrument) {
        try {
            String h1Swing = null, h1Internal = null, h1Break = null;
            String h4Swing = null, h4Break = null;
            String dailySwing = null;

            // H1 snapshot
            try {
                IndicatorSnapshot h1 = indicatorService.computeSnapshot(instrument, "1h");
                if (h1 != null) {
                    h1Swing = h1.swingBias();
                    h1Internal = h1.internalBias();
                    h1Break = h1.lastBreakType();
                }
            } catch (Exception e) {
                log.trace("No H1 snapshot for {}: {}", instrument, e.getMessage());
            }

            // H4 snapshot
            try {
                IndicatorSnapshot h4 = indicatorService.computeSnapshot(instrument, "4h");
                if (h4 != null) {
                    h4Swing = h4.swingBias();
                    h4Break = h4.lastBreakType();
                }
            } catch (Exception e) {
                log.trace("No H4 snapshot for {}: {}", instrument, e.getMessage());
            }

            // Daily snapshot
            try {
                IndicatorSnapshot daily = indicatorService.computeSnapshot(instrument, "1d");
                if (daily != null) {
                    dailySwing = daily.swingBias();
                }
            } catch (Exception e) {
                log.trace("No Daily snapshot for {}: {}", instrument, e.getMessage());
            }

            return new AgentContext.MtfSnapshot(h1Swing, h1Internal, h4Swing, dailySwing, h1Break, h4Break);
        } catch (Exception e) {
            log.debug("Failed to build MTF snapshot: {}", e.getMessage());
            return AgentContext.MtfSnapshot.empty();
        }
    }

    private AgentContext.MomentumSnapshot buildMomentumSnapshot(IndicatorSnapshot snap) {
        if (snap == null) return AgentContext.MomentumSnapshot.empty();

        return new AgentContext.MomentumSnapshot(
            snap.rsi(),
            snap.rsiSignal(),
            snap.macdHistogram(),
            snap.macdCrossover(),
            snap.wtWt1(),
            snap.wtWt2(),
            snap.wtSignal(),
            snap.bbPct(),
            snap.bbTrendExpanding(),
            snap.bbTrendSignal(),
            snap.supertrendBullish(),
            snap.stochSignal(),
            snap.stochCrossover()
        );
    }

    private AgentContext.SessionInfo buildSessionInfo(Instrument instrument) {
        try {
            Instant now = Instant.now();
            var phase = TradingSessionResolver.currentPhase(now);
            boolean killZone = TradingSessionResolver.isWithinKillZone(now);
            boolean marketOpen = TradingSessionResolver.isMarketOpen(now, instrument);
            boolean maintenance = TradingSessionResolver.isStandardMaintenanceWindow(now);

            return new AgentContext.SessionInfo(
                phase != null ? phase.name() : null,
                killZone,
                marketOpen,
                maintenance
            );
        } catch (Exception e) {
            log.debug("Failed to build session info: {}", e.getMessage());
            return AgentContext.SessionInfo.empty();
        }
    }
}
