package com.riskdesk.domain.execution.port;

import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Supplies the current top-of-book quote for an instrument so the router can price a reducing (exit) leg
 * as a <em>marketable limit</em> — a LIMIT priced THROUGH the market that fills immediately like a market
 * order, while the limit caps worst-case slippage. This keeps the deliberately limit-only broker path
 * (see {@code IbGatewayNativeClient#placeLimitOrder}) yet makes exits reliable.
 *
 * <p>Returns {@link Optional#empty()} when no live quote is available (cold cache, not connected, no
 * subscription yet). The router then falls back to the intent's passive limit price — strictly no worse
 * than the legacy behaviour. Only EXIT legs use this; entries stay passive by design.</p>
 *
 * <p>Functional interface so tests can pass a lambda directly.</p>
 */
@FunctionalInterface
public interface MarketQuoteProvider {

    /** Current quote for the instrument, or empty when no live quote can be obtained. */
    Optional<Quote> currentQuote(Instrument instrument);

    /**
     * Top-of-book snapshot. Any field may be {@code null} when that side has not ticked yet; the consumer
     * degrades gracefully (bid/ask preferred, {@code last} as a fallback reference).
     */
    record Quote(BigDecimal bid, BigDecimal ask, BigDecimal last) {
    }
}
