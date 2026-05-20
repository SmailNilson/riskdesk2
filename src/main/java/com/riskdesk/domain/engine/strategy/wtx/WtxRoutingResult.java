package com.riskdesk.domain.engine.strategy.wtx;

/**
 * The outcome of routing a WTX signal to IBKR auto-execution, together with an
 * optional human-readable error message.
 *
 * <p>The {@code errorMessage} is non-null only for failure or skip outcomes that
 * carry information worth surfacing to the operator (e.g. the broker's reject
 * reason, or the margin-check details for {@link WtxRoutingOutcome#SKIPPED_INSUFFICIENT_MARGIN}).
 * It is null for {@link WtxRoutingOutcome#ROUTED} and for self-explanatory skips
 * (e.g. {@link WtxRoutingOutcome#SKIPPED_AUTO_OFF}).</p>
 *
 * <p>The message is truncated upstream to keep the persistence + WebSocket payload
 * small (200 chars in the bridge, 300 chars in the entity column). Callers must
 * not embed PII or account-identifying broker fields here.</p>
 */
public record WtxRoutingResult(WtxRoutingOutcome outcome, String errorMessage) {

    public static WtxRoutingResult of(WtxRoutingOutcome outcome) {
        return new WtxRoutingResult(outcome, null);
    }

    public static WtxRoutingResult of(WtxRoutingOutcome outcome, String errorMessage) {
        return new WtxRoutingResult(outcome, errorMessage);
    }
}
