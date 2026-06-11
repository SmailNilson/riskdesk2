package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.event.FootprintBarClosed;
import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.domain.orderflow.port.FootprintPort;
import com.riskdesk.domain.orderflow.service.FootprintAggregator;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Infrastructure adapter that manages per-instrument {@link FootprintAggregator} instances.
 * Routes classified ticks to the correct aggregator, closes clock-aligned bars (tick-driven
 * roll-over plus an idle sweep via {@link #closeElapsedBars}), and publishes every closed
 * bar as a {@link FootprintBarClosed} event for persistence and WebSocket fan-out.
 *
 * <p>Bar duration and per-instrument price-bucket sizes come from
 * {@code riskdesk.order-flow.footprint.*}; instruments without a configured bucket size
 * fall back to their native tick size.</p>
 *
 * <p>Thread-safe: aggregators are created lazily and individual tick routing is synchronized
 * per aggregator instance.</p>
 */
@Component
public class IbkrFootprintAdapter implements FootprintPort {

    private static final Logger log = LoggerFactory.getLogger(IbkrFootprintAdapter.class);

    private final ConcurrentHashMap<Instrument, FootprintAggregator> aggregators = new ConcurrentHashMap<>();
    private final OrderFlowProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    public IbkrFootprintAdapter(OrderFlowProperties properties,
                                ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Routes a classified trade tick to the correct footprint aggregator.
     * Creates the aggregator lazily if it does not yet exist. If the tick rolls
     * the aggregator into a new bar window, the closed bar is published.
     *
     * @param instrument     the instrument this tick belongs to
     * @param price          trade price
     * @param size           number of contracts
     * @param classification "BUY" or "SELL"
     * @param timestamp      tick timestamp
     */
    public void onTick(Instrument instrument, double price, long size,
                       String classification, Instant timestamp) {
        FootprintAggregator aggregator = aggregators.computeIfAbsent(instrument, k -> {
            double bucketSize = bucketSizeFor(k);
            int barSeconds = barSeconds();
            double imbalanceRatio = properties.getFootprint().getImbalanceRatio();
            long minCellVolume = properties.getFootprint().minCellVolumeFor(k.name());
            log.info("Footprint: created aggregator for {} (bucketSize={}, barSeconds={}, imbalanceRatio={}, minCellVolume={})",
                     k, bucketSize, barSeconds, imbalanceRatio, minCellVolume);
            return new FootprintAggregator(k, bucketSize, barSeconds, imbalanceRatio, minCellVolume);
        });

        Optional<FootprintBar> closed;
        synchronized (aggregator) {
            closed = aggregator.onTick(price, size, classification, timestamp);
        }
        closed.ifPresent(this::publishClosed);
    }

    /**
     * Returns a snapshot of the current clock-aligned footprint bar for the instrument.
     * If no tick data has been accumulated in the current bar window, returns empty.
     */
    @Override
    public Optional<FootprintBar> currentBar(Instrument instrument) {
        FootprintAggregator aggregator = aggregators.get(instrument);
        if (aggregator == null) {
            return Optional.empty();
        }
        synchronized (aggregator) {
            return aggregator.currentBar();
        }
    }

    /**
     * Idle sweep: closes bars whose window elapsed without a new tick rolling them
     * over. Called periodically by the orchestrator; closed bars are published.
     */
    @Override
    public List<FootprintBar> closeElapsedBars(Instant now) {
        List<FootprintBar> closed = new ArrayList<>();
        aggregators.forEach((instrument, aggregator) -> {
            Optional<FootprintBar> bar;
            synchronized (aggregator) {
                bar = aggregator.closeIfElapsed(now);
            }
            bar.ifPresent(closed::add);
        });
        closed.forEach(this::publishClosed);
        return closed;
    }

    private void publishClosed(FootprintBar bar) {
        try {
            eventPublisher.publishEvent(new FootprintBarClosed(bar, Instant.now()));
        } catch (Exception e) {
            log.warn("Footprint: failed to publish closed bar for {}: {}", bar.instrument(), e.getMessage());
        }
    }

    private double bucketSizeFor(Instrument instrument) {
        Double configured = properties.getFootprint().getBucketSize().get(instrument.name());
        if (configured != null && configured > 0) {
            return configured;
        }
        return instrument.getTickSize().doubleValue();
    }

    private int barSeconds() {
        return Math.max(1, properties.getFootprint().getBarMinutes()) * 60;
    }
}
