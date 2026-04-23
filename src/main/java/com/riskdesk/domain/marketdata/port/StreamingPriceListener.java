package com.riskdesk.domain.marketdata.port;

import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Callback port for receiving real-time price updates pushed from the market data infrastructure.
 * Implementations must be non-blocking — heavy work should be offloaded to async executors.
 */
public interface StreamingPriceListener {
    void onLivePriceUpdate(Instrument instrument, BigDecimal price, Instant timestamp);
}
