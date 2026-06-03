package com.riskdesk.domain.notification.event;

import com.riskdesk.domain.shared.event.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Fired when the broker-truth daily loss cap trips: IBKR's realized P&L for the current trading day fell
 * to or below the configured negative threshold, so the guard halted NEW auto-entries (closes stay
 * allowed) until manually re-armed.
 *
 * <p>This is the P4 safety net for "plus jamais" (root cause: no broker-truth loss cutoff — the 2-day
 * bleed ran unchecked). It is informational + a hard stop: a push notification surfaces it immediately
 * and the guard already blocks further opening.</p>
 *
 * @param account       the broker account evaluated (or null for the default managed account)
 * @param realizedPnl   IBKR realized P&L for the trading day at trip time (negative)
 * @param threshold     the configured loss threshold (a positive USD magnitude; tripped when realizedPnl <= -threshold)
 * @param timestamp     when the cap tripped (UTC)
 */
public record DailyLossCapTrippedEvent(
        String account,
        BigDecimal realizedPnl,
        BigDecimal threshold,
        Instant timestamp
) implements DomainEvent {
}
