package com.riskdesk.domain.shared;

import java.time.Instant;

/**
 * An absolute time window during which a market is open for trading.
 *
 * <p>{@code open} is inclusive, {@code close} is exclusive: a tick at exactly
 * the close instant belongs to the maintenance window, not the trading session.
 *
 * @param open  session open (inclusive)
 * @param close session close (exclusive)
 */
public record TradingInterval(Instant open, Instant close) {

    /** Returns {@code true} if {@code t} falls within [{@code open}, {@code close}). */
    public boolean contains(Instant t) {
        return !t.isBefore(open) && t.isBefore(close);
    }
}
