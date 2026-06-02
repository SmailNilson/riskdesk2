package com.riskdesk.application.execution;

import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;

/**
 * Optional affordability gate the {@link DefaultOrderRouter} consults <b>after</b> reconcile and
 * <b>before</b> submitting an entry leg, so an order IBKR would reject for insufficient initial margin
 * (code 201) is stopped at the router — no transient {@code EXIT_SUBMITTED}, no orphaned ack, no
 * reject-vs-timeout race. Generalises the legacy {@code WtxExecutionBridge} pre-flight; the WTX
 * implementation ({@code IbkrMarginPreflightService}) backs it, so the unified path's deny decision is
 * byte-for-byte the legacy one.
 *
 * <p><b>Sized by the resolved entry quantity</b> — the router calls this only once it knows what the
 * reconcile produced: the full position for an {@code OPEN}, or only the net margin <i>delta</i>
 * (new size − prior live size) for a size-increasing {@code REVERSE}. A same-size or size-decreasing
 * REVERSE frees margin, so the router passes {@code qty <= 0} and the check is skipped.</p>
 *
 * <p><b>A denial never aborts a REVERSE close leg</b> — that is the router's responsibility: only the
 * open leg is skipped (the user ends up flat / protected → {@code ROUTED_FLATTEN_ONLY}); a pure OPEN
 * with no affordable margin is declined before any broker side effect ({@code
 * SKIPPED_INSUFFICIENT_MARGIN}).</p>
 *
 * <p><b>Fail-open.</b> Implementations allow the order whenever broker funds can't be read. The port is
 * OPTIONAL on the router — when no bean is present (the pre-flight is disabled, or the WTX-conditional
 * bean is absent) the router skips the check entirely, exactly as the legacy bridge's
 * {@code marginPreflight != null} guard did.</p>
 */
public interface OrderAffordabilityPort {

    /**
     * Decide whether an order of {@code qty} contracts at {@code refPrice} is affordable. Fails open on
     * missing broker data.
     *
     * @param instrument contract (its multiplier) for the margin estimate
     * @param action     {@code "LONG"} / {@code "SHORT"} — informational; futures consume margin
     *                   equally on either side
     * @param qty        contracts to pre-check ({@code > 0}); the router passes the full size for an
     *                   OPEN, or the net delta for a size-increasing REVERSE
     * @param refPrice   reference price for the estimate
     */
    Affordability check(Instrument instrument, String action, int qty, BigDecimal refPrice);

    /** Allow / deny verdict. {@code denyReason} is non-null only when {@code !allowed}. */
    record Affordability(boolean allowed, String denyReason) {
        public static Affordability allow() {
            return new Affordability(true, null);
        }

        public static Affordability deny(String reason) {
            return new Affordability(false, reason);
        }
    }
}
