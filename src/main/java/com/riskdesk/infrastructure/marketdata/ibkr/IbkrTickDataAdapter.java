package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
    private final OrderFlowProperties orderFlowProperties;

    /** Last trade price per instrument — the reference for the trade-to-trade tick rule (L2). */
    private final ConcurrentHashMap<Instrument, Double> lastTradePrice = new ConcurrentHashMap<>();
    /** Last tick-rule direction per instrument — carries through flat ticks (equal price). */
    private final ConcurrentHashMap<Instrument, TickByTickAggregator.TickClassification> lastTickRuleDir
        = new ConcurrentHashMap<>();

    /** Wall-clock instant of the last classified (BUY/SELL) tick — drives the delta watchdog (L3). */
    private final ConcurrentHashMap<Instrument, Instant> lastClassifiedAt = new ConcurrentHashMap<>();
    /** Trade time of the last classified tick — the dataTimestamp for the staleness heartbeat (L4). */
    private final ConcurrentHashMap<Instrument, Instant> lastGenuineWindowEnd = new ConcurrentHashMap<>();
    /** Total classified ticks across all instruments — the gap vs raw ticks = UNCLASSIFIED drops. */
    private final AtomicLong classifiedTicks = new AtomicLong(0);

    public IbkrTickDataAdapter(IbkrFootprintAdapter footprintAdapter, OrderFlowProperties orderFlowProperties) {
        this.footprintAdapter = footprintAdapter;
        this.orderFlowProperties = orderFlowProperties;
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
    public Optional<TickAggregation> recentAggregation(Instrument instrument, long windowSeconds) {
        TickByTickAggregator aggregator = aggregators.get(instrument);
        if (aggregator != null && aggregator.hasData()) {
            return Optional.of(aggregator.snapshotWindow(windowSeconds));
        }
        return Optional.empty();
    }

    @Override
    public Optional<TickAggregation> currentAggregationReadOnly(Instrument instrument) {
        TickByTickAggregator aggregator = aggregators.get(instrument);
        if (aggregator != null && aggregator.hasData()) {
            return Optional.of(aggregator.snapshotReadOnly());
        }
        return Optional.empty();
    }

    @Override
    public boolean isRealTickDataAvailable(Instrument instrument) {
        TickByTickAggregator aggregator = aggregators.get(instrument);
        return aggregator != null && aggregator.hasData();
    }

    @Override
    public Optional<Instant> lastClassifiedAt(Instrument instrument) {
        return Optional.ofNullable(lastClassifiedAt.get(instrument));
    }

    @Override
    public Optional<Instant> lastGenuineWindowEnd(Instrument instrument) {
        return Optional.ofNullable(lastGenuineWindowEnd.get(instrument));
    }

    @Override
    public long classifiedTicksReceived() {
        return classifiedTicks.get();
    }

    /**
     * Called by the IBKR native client when a tick-by-tick trade is received.
     * This method is thread-safe and can be called from the EReader thread.
     *
     * <p>When the quote-based Lee-Ready {@code classification} is UNCLASSIFIED (no fresh BBO/quote
     * was available) and the tick-rule fallback is enabled, the trade is classified by its
     * direction relative to the previous trade (uptick=BUY / downtick=SELL) and flagged
     * {@code tickRule} so the window's source degrades to {@code REAL_TICKS_TICKRULE} rather than
     * the tick being dropped — which previously zeroed delta in any no-quote period (L2).
     */
    public void onTickByTickTrade(Instrument instrument, double price, long size,
                                   TickByTickAggregator.TickClassification classification,
                                   java.time.Instant timestamp) {
        TickByTickAggregator.TickClassification resolved = classification;
        boolean tickRule = false;

        if (resolved == TickByTickAggregator.TickClassification.UNCLASSIFIED
                && orderFlowProperties.getTickByTick().isTickRuleFallbackEnabled()) {
            TickByTickAggregator.TickClassification byRule = classifyByTickRule(
                price, lastTradePrice.get(instrument), lastTickRuleDir.get(instrument));
            if (byRule != TickByTickAggregator.TickClassification.UNCLASSIFIED) {
                resolved = byRule;
                tickRule = true;
            }
        }

        // Always advance the tick-rule reference price; remember the last resolved direction so a
        // flat (equal-price) tick can carry it forward.
        lastTradePrice.put(instrument, price);
        if (resolved != TickByTickAggregator.TickClassification.UNCLASSIFIED) {
            lastTickRuleDir.put(instrument, resolved);
            // Classified-tick yield (not raw arrival) — the watchdog/heartbeat freshness signal.
            lastClassifiedAt.put(instrument, Instant.now());
            lastGenuineWindowEnd.put(instrument, timestamp);
            classifiedTicks.incrementAndGet();
        }

        TickByTickAggregator aggregator = aggregators.computeIfAbsent(
            instrument, k -> new TickByTickAggregator(k,
                orderFlowProperties.getTickByTick().getRealTicksMinQuoteFraction()));
        aggregator.onTick(price, size, resolved, tickRule, timestamp);

        // Route to footprint aggregator for per-price-level volume profiling
        if (resolved != TickByTickAggregator.TickClassification.UNCLASSIFIED) {
            footprintAdapter.onTick(instrument, price, size, resolved.name(), timestamp);
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

    /**
     * Trade-to-trade tick rule, used only when Lee-Ready cannot classify (no fresh BBO/quote):
     * uptick=BUY, downtick=SELL, and a flat tick carries the prior direction. With no usable
     * previous price the trade stays UNCLASSIFIED (the caller then drops it, as before).
     */
    public static TickByTickAggregator.TickClassification classifyByTickRule(
            double tradePrice, Double prevPrice, TickByTickAggregator.TickClassification prevDir) {
        if (prevPrice == null || prevPrice.isNaN() || prevPrice <= 0 || tradePrice <= 0) {
            return prevDir != null ? prevDir : TickByTickAggregator.TickClassification.UNCLASSIFIED;
        }
        if (tradePrice > prevPrice) {
            return TickByTickAggregator.TickClassification.BUY;
        }
        if (tradePrice < prevPrice) {
            return TickByTickAggregator.TickClassification.SELL;
        }
        return prevDir != null ? prevDir : TickByTickAggregator.TickClassification.UNCLASSIFIED;
    }
}
