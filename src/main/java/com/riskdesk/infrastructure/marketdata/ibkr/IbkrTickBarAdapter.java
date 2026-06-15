package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.TickBar;
import com.riskdesk.domain.orderflow.port.TickBarPort;
import com.riskdesk.domain.orderflow.service.TickBarAggregator;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Infrastructure adapter that manages per-instrument {@link TickBarAggregator}
 * instances for the tick chart. Bar size (ticks per bar) and ring-buffer depth come
 * from {@code riskdesk.order-flow.tick-chart.*}.
 *
 * <p>Thread-safe: aggregators are created lazily; tick routing and snapshots are
 * synchronized per aggregator instance.</p>
 */
@Component
public class IbkrTickBarAdapter implements TickBarPort {

    private static final Logger log = LoggerFactory.getLogger(IbkrTickBarAdapter.class);

    private final ConcurrentHashMap<Instrument, TickBarAggregator> aggregators = new ConcurrentHashMap<>();
    private final OrderFlowProperties properties;

    public IbkrTickBarAdapter(OrderFlowProperties properties) {
        this.properties = properties;
    }

    /**
     * Routes a classified trade to the instrument's tick-bar aggregator.
     */
    public void onTick(Instrument instrument, double price, long size,
                       String classification, Instant timestamp) {
        TickBarAggregator aggregator = aggregators.computeIfAbsent(instrument, k -> {
            int ticksPerBar = ticksPerBarFor(k);
            int maxBars = properties.getTickChart().getMaxBars();
            log.info("TickChart: created aggregator for {} (ticksPerBar={}, maxBars={})",
                     k, ticksPerBar, maxBars);
            return new TickBarAggregator(k, ticksPerBar, maxBars);
        });
        synchronized (aggregator) {
            aggregator.onTick(price, size, classification, timestamp);
        }
    }

    /**
     * Discards the instrument's tick-bar aggregator on a contract rollover, so the new contract
     * builds fresh bars instead of folding the old→new price gap into one giant seam bar. A new
     * aggregator is recreated lazily on the next trade.
     */
    public void purgeInstrument(Instrument instrument) {
        if (aggregators.remove(instrument) != null) {
            log.info("TickChart: cleared tick-bar aggregator for {} on contract rollover", instrument);
        }
    }

    @Override
    public List<TickBar> recentBars(Instrument instrument, int limit) {
        TickBarAggregator aggregator = aggregators.get(instrument);
        if (aggregator == null) {
            return List.of();
        }
        synchronized (aggregator) {
            return aggregator.recentBars(limit);
        }
    }

    private int ticksPerBarFor(Instrument instrument) {
        Integer configured = properties.getTickChart().getTicksPerBar().get(instrument.name());
        return configured != null && configured > 0
            ? configured
            : properties.getTickChart().getDefaultTicksPerBar();
    }
}
