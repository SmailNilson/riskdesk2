package com.riskdesk.domain.marketdata.port;

import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Port (interface) for fetching market data prices from the internal market-data pipeline.
 * In this project the allowed source is IBKR only.
 */
public interface MarketDataProvider {
    Map<Instrument, BigDecimal> fetchPrices();

    default Optional<BigDecimal> fetchPrice(Instrument instrument) {
        return Optional.ofNullable(fetchPrices().get(instrument));
    }

    /**
     * Fetches the latest VIX continuous futures price (CFE: CONTFUT).
     * Returns empty if the subscription is not active or the adapter does not support it.
     */
    default Optional<BigDecimal> fetchVixPrice() {
        return Optional.empty();
    }
}
