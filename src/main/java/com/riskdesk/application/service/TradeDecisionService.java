package com.riskdesk.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.decision.model.TradeDecision;
import com.riskdesk.domain.decision.port.DecisionNarratorPort;
import com.riskdesk.domain.decision.port.NarratorRequest;
import com.riskdesk.domain.decision.port.NarratorResponse;
import com.riskdesk.domain.decision.port.TradeDecisionRepositoryPort;
import com.riskdesk.domain.engine.playbook.agent.AgentVerdict;
import com.riskdesk.domain.engine.playbook.model.FinalVerdict;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.playbook.model.PlaybookPlan;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Records trade decisions produced by {@link AgentOrchestratorService}. Each call to
 * {@link #record(FinalVerdict, PlaybookEvaluation, String, String)} persists a
 * {@link TradeDecision}, asks {@link DecisionNarratorPort} for a human-readable paragraph,
 * and publishes the result to {@code /topic/trade-decisions}.
 *
 * <p>Scope of PR 1 is additive: this service runs in parallel with
 * {@code MentorSignalReviewService}. PR 2 does the UI + execution cutover; PR 3 removes
 * the Mentor path.
 *
 * <p>Distinct from Mentor in that:
 * <ul>
 *   <li>It does <b>not</b> re-decide the verdict — orchestrator is authoritative.</li>
 *   <li>Narrator is a single cheap call ({@code ~$0.005}) with a strict "do not decide"
 *       system prompt.</li>
 *   <li>Failure to narrate still persists the decision with a templated fallback line.</li>
 * </ul>
 */
@Service
public class TradeDecisionService {

    private static final Logger log = LoggerFactory.getLogger(TradeDecisionService.class);
    private static final String WS_TOPIC = "/topic/trade-decisions";

    private final TradeDecisionRepositoryPort repository;
    private final DecisionNarratorPort narrator;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public TradeDecisionService(TradeDecisionRepositoryPort repository,
                                DecisionNarratorPort narrator,
                                SimpMessagingTemplate messagingTemplate,
                                ObjectMapper objectMapper) {
        this.repository = repository;
        this.narrator = narrator;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Persist a new decision (revision 1) and narrate it.
     *
     * <p>Called by {@code SignalConfluenceBuffer} after the agent gate passes. Never throws —
     * on any persistence error, logs and returns empty so the upstream flow (Mentor review,
     * WebSocket publish, Telegram) proceeds unaffected.
     */
    public Optional<TradeDecision> record(FinalVerdict verdict,
                                          PlaybookEvaluation playbook,
                                          String instrument,
                                          String timeframe) {
        try {
            TradeDecision initial = buildInitial(verdict, playbook, instrument, timeframe);
            TradeDecision saved = repository.save(initial);
            TradeDecision narrated = narrateAndSave(saved, verdict);
            publish(narrated);
            log.info("Trade decision recorded id={} {} {} {} eligibility={} size={}",
                narrated.id(), instrument, timeframe, narrated.direction(),
                narrated.eligibility(), narrated.sizePercent());
            return Optional.of(narrated);
        } catch (Exception e) {
            log.error("Failed to record trade decision for {} {}: {}",
                instrument, timeframe, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Persist a new revision of an existing decision — same setup re-evaluated with fresh
     * verdicts. If no prior decision matches the thread key, falls back to revision 1.
     */
    public Optional<TradeDecision> recordRevision(FinalVerdict verdict,
                                                  PlaybookEvaluation playbook,
                                                  String instrument,
                                                  String timeframe) {
        String direction = directionOf(playbook);
        String zoneName = zoneNameOf(playbook);
        int nextRevision = 1;
        if (zoneName != null) {
            List<TradeDecision> thread = repository.findThread(instrument, timeframe, direction, zoneName);
            if (!thread.isEmpty()) {
                nextRevision = thread.get(thread.size() - 1).revision() + 1;
            }
        }

        try {
            TradeDecision initial = buildInitial(verdict, playbook, instrument, timeframe)
                .withId(null);
            TradeDecision withRev = new TradeDecision(
                null, nextRevision, initial.createdAt(),
                initial.instrument(), initial.timeframe(), initial.direction(),
                initial.setupType(), initial.zoneName(),
                initial.eligibility(), initial.sizePercent(), initial.verdict(),
                initial.agentVerdictsJson(), initial.warningsJson(),
                initial.entryPrice(), initial.stopLoss(),
                initial.takeProfit1(), initial.takeProfit2(), initial.rrRatio(),
                initial.narrative(), initial.narrativeModel(), initial.narrativeLatencyMs(),
                initial.status(), initial.errorMessage()
            );
            TradeDecision saved = repository.save(withRev);
            TradeDecision narrated = narrateAndSave(saved, verdict);
            publish(narrated);
            return Optional.of(narrated);
        } catch (Exception e) {
            log.error("Failed to record revision for {} {}: {}", instrument, timeframe, e.getMessage(), e);
            return Optional.empty();
        }
    }

    public Optional<TradeDecision> findById(Long id) {
        return repository.findById(id);
    }

    public List<TradeDecision> recent(int limit) {
        return repository.findRecent(Math.max(1, Math.min(limit, 500)));
    }

    public List<TradeDecision> recentByInstrument(String instrument, int limit) {
        return repository.findRecentByInstrument(instrument, Math.max(1, Math.min(limit, 500)));
    }

    public List<TradeDecision> thread(String instrument, String timeframe,
                                      String direction, String zoneName) {
        return repository.findThread(instrument, timeframe, direction, zoneName);
    }

    // ── Internals ────────────────────────────────────────────────────────

    private TradeDecision buildInitial(FinalVerdict verdict, PlaybookEvaluation playbook,
                                       String instrument, String timeframe) {
        PlaybookPlan plan = verdict.adjustedPlan() != null ? verdict.adjustedPlan() : playbook.plan();
        SetupCandidate setup = playbook.bestSetup();
        return new TradeDecision(
            null, 1, Instant.now(),
            instrument,
            timeframe,
            directionOf(playbook),
            setup != null && setup.type() != null ? setup.type().name() : null,
            setup != null ? setup.zoneName() : null,
            verdict.eligibility(),
            verdict.sizePercent(),
            verdict.verdict(),
            toJsonSafe(verdict.agentVerdicts()),
            toJsonSafe(verdict.warnings()),
            plan != null ? plan.entryPrice() : null,
            plan != null ? plan.stopLoss() : null,
            plan != null ? plan.takeProfit1() : null,
            plan != null ? plan.takeProfit2() : null,
            plan != null ? plan.rrRatio() : null,
            null, null, null,
            TradeDecision.STATUS_NARRATING,
            null
        );
    }

    private TradeDecision narrateAndSave(TradeDecision saved, FinalVerdict verdict) {
        try {
            NarratorRequest nr = toNarratorRequest(saved, verdict);
            NarratorResponse response = narrator.narrate(nr);
            TradeDecision narrated = response.available()
                ? saved.withNarration(response.narrative(), response.model(), response.latencyMs())
                : saved.withNarration(response.narrative(), null, 0L); // fallback text
            return repository.save(narrated);
        } catch (Exception e) {
            log.warn("Narration failed for decision id={}: {}", saved.id(), e.getMessage());
            TradeDecision errored = saved.withError("Narration error: " + e.getClass().getSimpleName());
            return repository.save(errored);
        }
    }

    private NarratorRequest toNarratorRequest(TradeDecision d, FinalVerdict verdict) {
        List<NarratorRequest.AgentVerdictLine> lines = new ArrayList<>();
        if (verdict.agentVerdicts() != null) {
            for (AgentVerdict v : verdict.agentVerdicts()) {
                lines.add(new NarratorRequest.AgentVerdictLine(
                    v.agentName(),
                    v.confidence() != null ? v.confidence().name() : "UNKNOWN",
                    v.reasoning()
                ));
            }
        }
        return new NarratorRequest(
            d.instrument(), d.timeframe(), d.direction(),
            d.setupType(), d.zoneName(),
            d.eligibility(), d.sizePercent(),
            d.entryPrice(), d.stopLoss(), d.takeProfit1(), d.takeProfit2(), d.rrRatio(),
            lines,
            verdict.warnings(),
            "fr"
        );
    }

    private void publish(TradeDecision decision) {
        if (messagingTemplate == null) return;
        try {
            messagingTemplate.convertAndSend(WS_TOPIC, decision);
        } catch (Exception e) {
            log.warn("WS publish failed for decision id={}: {}", decision.id(), e.getMessage());
        }
    }

    private String toJsonSafe(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize {} to JSON: {}",
                value.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private static String directionOf(PlaybookEvaluation playbook) {
        return playbook.filters() != null && playbook.filters().tradeDirection() != null
            ? playbook.filters().tradeDirection().name()
            : "FLAT";
    }

    private static String zoneNameOf(PlaybookEvaluation playbook) {
        return playbook.bestSetup() != null ? playbook.bestSetup().zoneName() : null;
    }
}
