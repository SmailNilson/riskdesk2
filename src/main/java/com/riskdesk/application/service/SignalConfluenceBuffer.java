package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.SignalWeight;
import com.riskdesk.domain.engine.playbook.agent.AgentContext;
import com.riskdesk.domain.engine.playbook.model.FinalVerdict;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Confluence Engine — Standalone Flush model.
 *
 * <p>Qualified signals accumulate per (instrument, timeframe, direction)
 * within a fixed time window. When cumulative weight reaches the flush
 * threshold (3.0), flushes immediately to Gemini.
 *
 * <p>With standalone weights (CHoCH=3.0, WAVETREND=3.0, BOS=3.0, etc.),
 * a single strong signal triggers an immediate review. Secondary signals
 * (EMA=1.0, RSI=1.0, etc.) need confluence to reach the threshold.
 *
 * <p>Sub-threshold buffers are logged for backtest analysis and discarded
 * when their accumulation window expires.
 *
 * <p>Thread safety: {@link ConcurrentHashMap#compute} ensures atomicity per buffer key.
 */
@Service
public class SignalConfluenceBuffer {

    private static final Logger log = LoggerFactory.getLogger(SignalConfluenceBuffer.class);
    private static final float FLUSH_THRESHOLD = 3.0f;

    /**
     * Families that count as "structural anchor" — Gemini's decision hierarchy is
     * Structure 50% > Order Flow 30% > Momentum 20%. A confluence flush without
     * at least one structural signal produces reviews that Gemini systematically rejects.
     */
    private static final Set<String> STRUCTURAL_FAMILIES = Set.of(
            "Structure",     // ORDER_BLOCK
            "SMC",           // CHoCH, BOS
            "Liquidite",     // EQH/EQL sweeps
            "Structure_FVG"  // Fair Value Gaps
    );

    private final ConcurrentHashMap<String, BufferEntry> buffers = new ConcurrentHashMap<>();
    private final MentorSignalReviewService mentorSignalReviewService;

    /**
     * Optional pre-Mentor agent gate. When wired, a flush that would normally trigger
     * a Gemini review is first evaluated by the 4-agent orchestrator
     * (SessionTiming + MtfConfluence + OrderFlow + ZoneQuality) and the deterministic
     * risk gate. If the orchestrator returns BLOCKED or INELIGIBLE, we skip the
     * expensive Gemini review — saving ~$0.04 per rejected signal.
     *
     * <p>All nullable so the buffer keeps working if the agent wiring is down.
     */
    private AgentOrchestratorService agentOrchestrator;
    private PlaybookService playbookService;

    /**
     * Optional {@link TradeDecisionService} — when wired, each flush that passes the agent
     * gate also persists a {@code TradeDecision} (additive to the Mentor review). This is
     * the PR 1 step of the Mentor → TradeDecision migration: both paths run in parallel;
     * the Mentor path is removed in a later PR once the frontend cutover is done.
     */
    private TradeDecisionService tradeDecisionService;

    public SignalConfluenceBuffer(MentorSignalReviewService mentorSignalReviewService) {
        this.mentorSignalReviewService = mentorSignalReviewService;
    }

    @Autowired(required = false)
    public void setAgentOrchestrator(AgentOrchestratorService agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
    }

    @Autowired(required = false)
    public void setPlaybookService(PlaybookService playbookService) {
        this.playbookService = playbookService;
    }

    @Autowired(required = false)
    public void setTradeDecisionService(TradeDecisionService tradeDecisionService) {
        this.tradeDecisionService = tradeDecisionService;
    }

    /**
     * Accumulates a qualified signal into the buffer for the given instrument/timeframe/direction.
     * If the cumulative weight reaches {@value #FLUSH_THRESHOLD}, flushes immediately to Gemini.
     */
    public void accumulate(Alert alert, String timeframe, String direction,
                           IndicatorSnapshot snap, SignalWeight sw) {
        String key = alert.instrument() + ":" + timeframe + ":" + direction;
        buffers.compute(key, (k, existing) -> {
            if (existing == null) {
                return new BufferEntry(alert, snap, sw, timeframe);
            }
            existing.addSignal(alert, snap, sw);
            return existing;
        });
        // Immediate flush check — outside compute() to avoid nested CHM operations
        BufferEntry entry = buffers.get(key);
        if (entry != null && entry.effectiveWeight() >= FLUSH_THRESHOLD) {
            flush(key);
        }
    }

    /**
     * Polls every 5s for buffers whose fixed window has expired.
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 30_000)
    public void flushExpiredBuffers() {
        Instant now = Instant.now();
        buffers.forEach((key, entry) -> {
            long windowSeconds = windowForTimeframe(entry.timeframe);
            if (now.isAfter(entry.firstSignalTime.plusSeconds(windowSeconds))) {
                flush(key);
            }
        });
    }

    private void flush(String key) {
        BufferEntry entry = buffers.remove(key);
        if (entry == null) return;

        float weight = entry.effectiveWeight();
        Alert primary = entry.primarySignal();

        if (weight >= FLUSH_THRESHOLD && primary != null) {
            // Structural anchor check: reject pure oscillator/momentum accumulation.
            // Gemini's hierarchy is Structure 50% > Flow 30% > Momentum 20%.
            // Without structural backing, reviews are systematically rejected.
            if (!entry.hasStructuralAnchor()) {
                log.info("CONFLUENCE REJECTED [{}] weight={} signals={} — no structural anchor, "
                        + "pure oscillator/momentum noise discarded", key, weight, entry.signals.size());
                return;
            }

            // Read opposing buffer weight
            String oppositeKey = oppositeKey(key);
            float opposingWeight = Optional.ofNullable(buffers.get(oppositeKey))
                    .map(BufferEntry::effectiveWeight).orElse(0f);

            log.info("CONFLUENCE FLUSH [{}] weight={} signals={} primary={} opposing={}",
                    key, weight, entry.signals.size(), primary.category(), opposingWeight);

            // ── Pre-Mentor agent gate (session + MTF + OrderFlow + ZoneQuality + Risk) ─
            // Saves ~$0.04 per rejected signal by skipping the Gemini mentor review
            // when the orchestrator says the setup is blocked or clearly ineligible.
            AgentGateResult gate = evaluateAgentGate(key, entry);
            if (gate.blocked()) {
                log.info("AGENT GATE BLOCKED [{}] {} — Mentor review skipped ({})",
                        key, gate.reason(), gate.eligibility());
                return;
            }
            if (gate.evaluated()) {
                log.info("AGENT GATE PASSED [{}] eligibility={} sizePct={}",
                        key, gate.eligibility(), String.format("%.4f", gate.sizePct()));
                // Additive (PR 1): also persist as a TradeDecision. Mentor review still
                // fires below until PR 2 (frontend cutover) and PR 3 (Mentor removal).
                recordTradeDecision(key, gate);
            }

            mentorSignalReviewService.captureConsolidatedReview(
                    entry.signals, entry.latestSnapshot, weight, primary, opposingWeight);
        } else {
            log.debug("CONFLUENCE EXPIRED [{}] weight={} signals={} — below threshold, skipped",
                    key, weight, entry.signals.size());
        }
    }

    static long windowForTimeframe(String tf) {
        return switch (tf) {
            case "5m"  -> 60;
            case "10m" -> 120;
            case "1h"  -> 300;
            default    -> 120;
        };
    }

    static String oppositeKey(String key) {
        if (key.endsWith(":LONG"))  return key.substring(0, key.length() - 4) + "SHORT";
        if (key.endsWith(":SHORT")) return key.substring(0, key.length() - 5) + "LONG";
        return key;
    }

    /**
     * Runs the 4-agent orchestrator + risk gate on the buffer's latest snapshot.
     * Returns an {@link AgentGateResult#notEvaluated()} when any prerequisite is
     * missing (orchestrator not wired, snapshot null, no playbook setup, etc.) so
     * the flow falls through to Mentor review as before.
     */
    private AgentGateResult evaluateAgentGate(String key, BufferEntry entry) {
        if (agentOrchestrator == null || playbookService == null) {
            return AgentGateResult.notEvaluated();
        }
        if (entry.latestSnapshot == null) {
            return AgentGateResult.notEvaluated();
        }

        // Parse "INSTRUMENT:timeframe:direction" key
        String[] parts = key.split(":", 3);
        if (parts.length != 3) return AgentGateResult.notEvaluated();

        Instrument instrument;
        try {
            instrument = Instrument.valueOf(parts[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            log.debug("Agent gate: unknown instrument {}", parts[0]);
            return AgentGateResult.notEvaluated();
        }
        String timeframe = parts[1];

        try {
            BigDecimal atr = BigDecimal.ONE; // PlaybookService will use snapshot-based heuristic
            PlaybookEvaluation playbook = playbookService.evaluateFromSnapshot(entry.latestSnapshot, atr);
            if (playbook.bestSetup() == null || playbook.plan() == null) {
                return AgentGateResult.notEvaluated();
            }
            AgentContext context = agentOrchestrator.buildContext(
                instrument, timeframe, entry.latestSnapshot, atr, playbook);

            FinalVerdict verdict = agentOrchestrator.orchestrate(playbook, context);
            boolean blocked = "BLOCKED".equals(verdict.eligibility())
                           || "INELIGIBLE".equals(verdict.eligibility());
            String reason = verdict.warnings() != null && !verdict.warnings().isEmpty()
                ? verdict.warnings().get(0) : verdict.verdict();
            return new AgentGateResult(true, blocked, verdict.eligibility(), reason,
                verdict.sizePercent(), verdict, playbook, instrument.name(), timeframe);
        } catch (Exception e) {
            log.warn("Agent gate evaluation failed for {}: {}", key, e.getMessage());
            return AgentGateResult.notEvaluated();
        }
    }

    /** Persists the agent verdict as a {@link com.riskdesk.domain.decision.model.TradeDecision}. */
    private void recordTradeDecision(String key, AgentGateResult gate) {
        if (tradeDecisionService == null || gate.verdict() == null || gate.playbook() == null) {
            return;
        }
        try {
            tradeDecisionService.record(gate.verdict(), gate.playbook(),
                gate.instrument(), gate.timeframe());
        } catch (Exception e) {
            // Never block the Mentor flow on a TradeDecision failure.
            log.warn("TradeDecision record failed for {}: {}", key, e.getMessage());
        }
    }

    /**
     * Outcome of the pre-Mentor agent gate.
     * {@code evaluated=false} means the gate was skipped (missing deps); let the flow
     * through to Mentor review. {@code blocked=true} means the orchestrator returned
     * BLOCKED or INELIGIBLE — skip the review to save Gemini cost.
     *
     * <p>Carries the full {@link FinalVerdict} and {@link PlaybookEvaluation} so the flush
     * path can record a {@link com.riskdesk.domain.decision.model.TradeDecision} without
     * re-running the orchestrator.
     */
    record AgentGateResult(boolean evaluated, boolean blocked, String eligibility,
                           String reason, double sizePct,
                           FinalVerdict verdict, PlaybookEvaluation playbook,
                           String instrument, String timeframe) {
        static AgentGateResult notEvaluated() {
            return new AgentGateResult(false, false, "NOT_EVALUATED", null, 0.0,
                null, null, null, null);
        }
    }

    // ── BufferEntry ────────────────────────────────────────────────────────────

    static class BufferEntry {
        final List<Alert> signals = new ArrayList<>();
        /** Non-cumul families: keeps max weight per family. */
        private final Map<String, Float> nonCumulWeights = new HashMap<>();
        /** Cumulative families: first-signal-wins per type. */
        private final Map<String, Float> cumulWeights = new HashMap<>();
        /** Tracks which signal families contributed to this buffer. */
        private final Set<String> presentFamilies = new HashSet<>();
        IndicatorSnapshot latestSnapshot;
        final Instant firstSignalTime;
        final String timeframe;

        BufferEntry(Alert alert, IndicatorSnapshot snap, SignalWeight sw, String timeframe) {
            this.latestSnapshot = snap;
            this.firstSignalTime = Instant.now();
            this.timeframe = timeframe;
            addSignal(alert, snap, sw);
        }

        void addSignal(Alert alert, IndicatorSnapshot snap, SignalWeight sw) {
            signals.add(alert);
            latestSnapshot = snap;
            presentFamilies.add(sw.family());
            if (SignalWeight.isNonCumulFamily(sw.family())) {
                nonCumulWeights.merge(sw.family(), sw.weight(), Math::max);
            } else {
                cumulWeights.merge(sw.family() + ":" + sw.name(), sw.weight(), (old, v) -> old);
            }
        }

        /**
         * Returns true if the buffer contains at least one signal from a structural family
         * (Structure, SMC, Liquidite, FVG). Pure oscillator/momentum/flow combinations
         * without structural backing are systematically rejected by Gemini.
         */
        boolean hasStructuralAnchor() {
            return presentFamilies.stream().anyMatch(STRUCTURAL_FAMILIES::contains);
        }

        float effectiveWeight() {
            float total = 0f;
            for (float w : nonCumulWeights.values()) total += w;
            for (float w : cumulWeights.values()) total += w;
            return total;
        }

        Alert primarySignal() {
            Alert best = null;
            int bestPriority = Integer.MAX_VALUE;
            for (Alert alert : signals) {
                SignalWeight sw = SignalWeight.fromAlert(alert);
                if (sw != null && sw.priority() < bestPriority) {
                    bestPriority = sw.priority();
                    best = alert;
                }
            }
            return best;
        }
    }
}
