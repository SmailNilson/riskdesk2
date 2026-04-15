package com.riskdesk.domain.engine.playbook.event;

import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.shared.event.DomainEvent;
import com.riskdesk.domain.model.Instrument;

import java.time.Instant;
import java.util.List;

/**
 * Emitted once every {@code AgentOrchestratorService.orchestrate(...)} call, regardless
 * of outcome (ELIGIBLE, INELIGIBLE, BLOCKED).
 *
 * <p>This is the orchestrator's audit trail — every time the system decides whether a
 * setup should proceed to Mentor review, we capture <em>who</em> weighed in, <em>what</em>
 * they said, and <em>how</em> the decision was aggregated. Listeners can persist the event
 * for backtesting ("which agent combinations correlate with winning trades?"),
 * analytics, or ML training data.
 *
 * <p>Kept intentionally flat: no nested playbook objects, no full {@code AgentContext}.
 * Listeners that need deep context can correlate via {@code (instrument, timeframe,
 * timestamp)} back to the source snapshot — we do not want to serialize large
 * domain records on every decision.
 *
 * <p>Lives in {@code domain} (no Spring imports) and is emitted via Spring's
 * {@code ApplicationEventPublisher} from the application-layer orchestrator.
 * The domain type stays framework-agnostic; the delivery mechanism does not.
 */
public record AgentDecisionEvent(
    Instrument instrument,
    String timeframe,
    String setupType,
    String eligibility,
    double sizePercent,
    List<AgentSummary> verdicts,
    int warningsCount,
    String finalVerdict,
    Instant timestamp
) implements DomainEvent {

    public AgentDecisionEvent {
        // Defensive copy so the event is truly immutable — a mutable list sneaking in
        // would let a downstream listener see a different snapshot than the emitter.
        verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
    }

    /**
     * Lightweight per-agent summary — name + verdict + one-line reasoning. Enough to
     * reconstruct the decision story without forcing listeners to know the full
     * {@code AgentVerdict} / {@code AgentAdjustments} record shape.
     */
    public record AgentSummary(
        String agentName,
        Confidence confidence,
        String reasoning,
        boolean blocked
    ) {}
}
