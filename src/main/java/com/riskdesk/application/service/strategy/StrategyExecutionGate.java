package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.infrastructure.config.StrategyExecutionGateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Execution-path concurrence gate — the S4 safety rail.
 *
 * <p>When an instrument is enrolled, every trade-execution attempt must be
 * approved by both the legacy Mentor review ({@code ExecutionEligibilityStatus.ELIGIBLE})
 * AND the probabilistic strategy engine (tradeable decision + matching direction).
 * The gate cannot <i>create</i> trades — only block trades the legacy path would
 * have approved. This is VETO_ONLY mode; a later slice introduces PRIMARY mode.
 *
 * <h2>Default behaviour</h2>
 * <ul>
 *   <li>Global switch off → {@link GateOutcome#pass(String)}, no-op. Production
 *       behaviour is unchanged unless operators opt in.</li>
 *   <li>Instrument not enrolled → pass.</li>
 *   <li>Strategy engine bean unavailable → block (fail-closed). If you've enrolled
 *       an instrument you've committed to the concurrence check; an engine outage
 *       should halt trading until resolved.</li>
 *   <li>Engine throws → block. Same rationale.</li>
 *   <li>Engine decision NO_TRADE / MONITORING / PAPER_TRADE → block.</li>
 *   <li>Engine direction doesn't match review action → block.</li>
 *   <li>Otherwise → pass, with the agreed playbook id and score logged as the
 *       reason (useful for downstream telemetry).</li>
 * </ul>
 *
 * <p>All block paths log at {@code WARN} so operators notice divergences.
 */
@Component
public class StrategyExecutionGate {

    private static final Logger log = LoggerFactory.getLogger(StrategyExecutionGate.class);

    private final StrategyExecutionGateProperties properties;
    /**
     * The strategy engine is optional at wiring time: a boot failure in the
     * strategy module shouldn't break the execution path entirely. We fail-closed
     * inside {@link #check(MentorSignalReviewRecord)} when the engine is absent
     * AND the instrument is enrolled — that way unenrolled instruments continue
     * working even during strategy-module incidents.
     */
    private final ObjectProvider<StrategyEngineService> engineProvider;

    public StrategyExecutionGate(StrategyExecutionGateProperties properties,
                                  ObjectProvider<StrategyEngineService> engineProvider) {
        this.properties = properties;
        this.engineProvider = engineProvider;
    }

    public GateOutcome check(MentorSignalReviewRecord review) {
        if (!properties.isEnabled()) {
            return GateOutcome.pass("gate-disabled-globally");
        }
        if (review == null || review.getInstrument() == null) {
            // Defensive: the caller already validated this, but never trust upstream.
            return GateOutcome.pass("review-instrument-missing");
        }
        String instrumentCode = review.getInstrument();
        if (!properties.enrolls(instrumentCode)) {
            return GateOutcome.pass("instrument-not-enrolled: " + instrumentCode);
        }

        StrategyEngineService engine = engineProvider.getIfAvailable();
        if (engine == null) {
            log.warn("Strategy execution gate blocked execution for {}: engine unavailable",
                instrumentCode);
            return GateOutcome.block("strategy-engine-unavailable");
        }

        // Narrow catch for enum parsing — the ONLY source of a legitimate
        // "unknown-instrument" label. Wrapping engine.evaluate() in the same
        // catch(IllegalArgumentException) would mislabel internal validation
        // failures (a pure-domain agent throwing IAE on a malformed context)
        // as "unknown-instrument", hiding real engine bugs from operators.
        Instrument instrument;
        try {
            instrument = Instrument.valueOf(instrumentCode);
        } catch (IllegalArgumentException e) {
            log.warn("Strategy execution gate blocked execution for {}: unknown instrument",
                instrumentCode);
            return GateOutcome.block("unknown-instrument: " + instrumentCode);
        }

        StrategyDecision decision;
        try {
            decision = engine.evaluate(instrument, review.getTimeframe());
        } catch (Exception e) {
            log.warn("Strategy execution gate blocked execution for {}: engine error: {}",
                instrumentCode, e.getMessage());
            return GateOutcome.block("engine-error: "
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }

        if (!decision.decision().isTradeable()) {
            log.warn("Strategy execution gate blocked execution for {} {}: decision={} score={}",
                instrumentCode, review.getTimeframe(),
                decision.decision(), String.format("%.1f", decision.finalScore()));
            return GateOutcome.block(String.format("engine-decision=%s score=%.1f",
                decision.decision(), decision.finalScore()), decision);
        }

        Direction engineDirection = decision.direction().orElse(null);
        String reviewAction = review.getAction();
        if (engineDirection == null || !engineDirection.name().equalsIgnoreCase(reviewAction)) {
            log.warn("Strategy execution gate blocked execution for {} {}: direction mismatch "
                + "(engine={}, review={})",
                instrumentCode, review.getTimeframe(), engineDirection, reviewAction);
            return GateOutcome.block(String.format("direction-mismatch engine=%s review=%s",
                engineDirection, reviewAction), decision);
        }

        String playbook = decision.candidatePlaybookId().orElse("?");
        log.info("Strategy execution gate PASSED for {} {}: playbook={} decision={} score={}",
            instrumentCode, review.getTimeframe(), playbook,
            decision.decision(), String.format("%.1f", decision.finalScore()));
        return GateOutcome.pass(String.format("engine-agrees playbook=%s decision=%s score=%.1f",
            playbook, decision.decision(), decision.finalScore()), decision);
    }
}
