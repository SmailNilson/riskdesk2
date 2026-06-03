package com.riskdesk.domain.notification.event;

import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Fired when the broker-truth reconciler forces an execution row to match what IBKR
 * actually holds — i.e. it corrected a divergence between the app's state and the broker.
 *
 * <p>This is the <b>divergence alarm</b> (root cause R7 in {@code docs/PLAN_EXECUTION_TRUTH_SYNC.md}):
 * without it, a phantom position / stuck row is only discovered by losing money. A push notification
 * on every correction surfaces the divergence within seconds instead of days. It is informational —
 * the reconciler has <i>already</i> realigned the app to a flat broker; no order was placed.</p>
 *
 * @param instrument    the instrument whose row was reconciled (e.g. {@code MNQ})
 * @param triggerSource the strategy that owned the row ({@code WTX_AUTO}, {@code QUANT_SIM_AUTO}, …); may be null
 * @param fromStatus    the non-terminal status the row was stuck in
 * @param toStatus      the terminal status it was forced to ({@code CLOSED} / {@code CANCELLED})
 * @param reason        human-readable reconciliation reason
 * @param timestamp     when the correction was applied (UTC)
 */
public record ExecutionReconciledEvent(
        String instrument,
        String triggerSource,
        String fromStatus,
        String toStatus,
        String reason,
        Instant timestamp
) implements DomainEvent {
}
