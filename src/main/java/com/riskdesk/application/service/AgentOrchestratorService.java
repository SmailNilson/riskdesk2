package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.engine.playbook.agent.*;
import com.riskdesk.domain.engine.playbook.model.FinalVerdict;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.playbook.model.PlaybookInput;
import com.riskdesk.domain.engine.playbook.model.PlaybookPlan;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class AgentOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorService.class);
    private static final Duration AGENT_TIMEOUT = Duration.ofSeconds(5);

    private final List<TradingAgent> agents;

    public AgentOrchestratorService() {
        // Domain agents — no Spring injection needed (pure domain)
        this.agents = List.of(
            new StructureAnalystAgent(),
            new RiskManagerAgent(),
            new MacroContextAgent()
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
            // Apply size adjustments from Risk Manager
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
            if (v.confidence() == com.riskdesk.domain.engine.playbook.model.Confidence.LOW) {
                warnings.add(v.agentName() + ": " + v.reasoning());
            }
        }

        String eligibility = blocked ? "INELIGIBLE" : (sizePct > 0 ? "ELIGIBLE" : "INELIGIBLE");

        PlaybookPlan adjustedPlan = blocked ? null : playbook.plan().withAdjustedSize(sizePct);

        String verdict;
        if (blocked) {
            verdict = "BLOCKED — " + warnings.get(0);
        } else if (warnings.isEmpty()) {
            verdict = playbook.verdict() + " — ALL AGENTS ALIGNED";
        } else {
            verdict = playbook.verdict() + " — " + warnings.size() + " warning(s)";
        }

        log.info("Orchestrator verdict: {} (size: {}%, agents: {})",
            verdict, String.format("%.4f", sizePct * 100), verdicts.size());

        return new FinalVerdict(verdict, adjustedPlan, sizePct, verdicts, warnings, eligibility);
    }

    public AgentContext buildContext(Instrument instrument, String timeframe,
                                     IndicatorSnapshot snapshot, BigDecimal atr) {
        PlaybookInput input = PlaybookService.toPlaybookInput(snapshot, atr != null ? atr : BigDecimal.ONE);
        String sessionPhase = snapshot != null ? snapshot.sessionPhase() : null;
        boolean isKillZone = "NEW_YORK".equalsIgnoreCase(sessionPhase)
                          || "LONDON".equalsIgnoreCase(sessionPhase);

        return new AgentContext(
            instrument, timeframe, input,
            AgentContext.PortfolioState.empty(),
            new AgentContext.MacroSnapshot(null, null, sessionPhase, isKillZone),
            atr
        );
    }
}
