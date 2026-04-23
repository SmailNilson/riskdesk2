package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Infrastructure adapter implementing the TickDataPort.
 * Manages per-instrument TickByTickAggregator instances.
 * In Phase 3B this is a skeleton; actual IBKR tick-by-tick subscription
 * will be wired when IbGatewayNativeClient exposes reqTickByTickData.
 */
@Component
public class IbkrTickDataAdapter implements TickDataPort {

    private static final Logger log = LoggerFactory.getLogger(IbkrTickDataAdapter.class);

    private final ConcurrentHashMap<Instrument, TickByTickAggregator> aggregators = new ConcurrentHashMap<>();
    private final IbkrFootprintAdapter footprintAdapter;

    public IbkrTickDataAdapter(IbkrFootprintAdapter footprintAdapter) {
        this.footprintAdapter = footprintAdapter;
    }

    @Override
    public Optional<TickAggregation> currentAggregation(Instrument instrument) {
        TickByTickAggregator aggregator = aggregators.get(instrument);
        if (aggregator != null && aggregator.hasData()) {
            return Optional.of(aggregator.snapshot());
        }
        return Optional.empty();
    }

    @Override
    public boolean isRealTickDataAvailable(Instrument instrument) {
        TickByTickAggregator aggregator = aggregators.get(instrument);
        return aggregator != null && aggregator.hasData();
    }

    /**
     * Called by the IBKR native client when a tick-by-tick trade is received.
     * This method is thread-safe and can be called from the EReader thread.
     */
    public void onTickByTickTrade(Instrument instrument, double price, long size,
                                   TickByTickAggregator.TickClassification classification,
                                   java.time.Instant timestamp) {
        TickByTickAggregator aggregator = aggregators.computeIfAbsent(
            instrument, k -> new TickByTickAggregator(k));
        aggregator.onTick(price, size, classification, timestamp);

        // Route to footprint aggregator for per-price-level volume profiling
        if (classification != TickByTickAggregator.TickClassification.UNCLASSIFIED) {
            footprintAdapter.onTick(instrument, price, size, classification.name(), timestamp);
        }
    }

    /**
     * Classify a trade using the Lee-Ready rule.
     * Trade at ask or above = BUY, at bid or below = SELL, between = proximity-based.
     */
    public static TickByTickAggregator.TickClassification classifyTrade(
            double tradePrice, double bid, double ask) {
        if (bid <= 0 || ask <= 0 || ask < bid) {
            return TickByTickAggregator.TickClassification.UNCLASSIFIED;
        }
        if (tradePrice >= ask) {
            return TickByTickAggregator.TickClassification.BUY;
        }
        if (tradePrice <= bid) {
            return TickByTickAggregator.TickClassification.SELL;
        }
        // Between bid and ask: classify by proximity (Lee-Ready midpoint rule)
        double mid = (bid + ask) / 2.0;
        if (tradePrice > mid) {
            return TickByTickAggregator.TickClassification.BUY;
        } else if (tradePrice < mid) {
            return TickByTickAggregator.TickClassification.SELL;
        }
        return TickByTickAggregator.TickClassification.UNCLASSIFIED;
    }
}
