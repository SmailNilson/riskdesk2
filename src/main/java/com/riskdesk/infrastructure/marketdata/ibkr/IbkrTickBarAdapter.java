package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.TickBar;
import com.riskdesk.domain.orderflow.port.TickBarPort;
import com.riskdesk.domain.orderflow.port.TickBarStorePort;
import com.riskdesk.domain.orderflow.service.TickBarAggregator;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Infrastructure adapter that manages per-instrument {@link TickBarAggregator}
 * instances for the tick chart. Bar size (ticks per bar) and ring-buffer depth come
 * from {@code riskdesk.order-flow.tick-chart.*}.
 *
 * <p>Completed bars are also persisted via {@link TickBarStorePort} (when
 * {@code tick-chart.persistence.enabled}) and reloaded on startup, so the chart
 * survives a redeploy instead of starting blank. Persisted seq is restored, keeping
 * the per-instrument sequence monotonic across restarts.</p>
 *
 * <p>Thread-safe: aggregators are created lazily; tick routing and snapshots are
 * synchronized per aggregator instance. Completed bars are buffered lock-free and
 * flushed to storage on a scheduler off the EReader thread.</p>
 */
@Component
public class IbkrTickBarAdapter implements TickBarPort {

    private static final Logger log = LoggerFactory.getLogger(IbkrTickBarAdapter.class);

    private final ConcurrentHashMap<Instrument, TickBarAggregator> aggregators = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TickBar> pendingPersist = new ConcurrentLinkedQueue<>();
    private final OrderFlowProperties properties;
    private final TickBarStorePort store;

    public IbkrTickBarAdapter(OrderFlowProperties properties, TickBarStorePort store) {
        this.properties = properties;
        this.store = store;
    }

    /**
     * Routes a classified trade to the instrument's tick-bar aggregator. A bar that
     * closes on this trade is queued for durable persistence.
     */
    public void onTick(Instrument instrument, double price, long size,
                       String classification, Instant timestamp) {
        TickBarAggregator aggregator = aggregators.computeIfAbsent(instrument, this::createAggregator);
        Optional<TickBar> completed;
        synchronized (aggregator) {
            completed = aggregator.onTick(price, size, classification, timestamp);
        }
        if (persistenceEnabled() && completed.isPresent()) {
            pendingPersist.add(completed.get());
        }
    }

    /**
     * Discards the instrument's tick-bar aggregator on a contract rollover, so the new contract
     * builds fresh bars instead of folding the old→new price gap into one giant seam bar. A new
     * aggregator is recreated lazily on the next trade. Persisted bars for the instrument are also
     * dropped so a later restart does not reload bars priced on the expired contract.
     */
    public void purgeInstrument(Instrument instrument) {
        if (aggregators.remove(instrument) != null) {
            log.info("TickChart: cleared tick-bar aggregator for {} on contract rollover", instrument);
        }
        if (persistenceEnabled()) {
            pendingPersist.removeIf(b -> instrument.name().equals(b.instrument()));
            try {
                store.purgeInstrument(instrument);
            } catch (Exception e) {
                log.warn("TickChart: failed to purge persisted bars for {}: {}", instrument, e.getMessage());
            }
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

    /**
     * Re-seeds each instrument's ring buffer from storage once the context is ready, so the
     * tick chart shows history immediately after a redeploy rather than waiting for new trades.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreFromStore() {
        if (!persistenceEnabled()) {
            return;
        }
        int maxBars = properties.getTickChart().getMaxBars();
        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            try {
                int ticksPerBar = ticksPerBarFor(instrument);
                List<TickBar> bars = store.loadRecent(instrument, ticksPerBar, maxBars);
                if (bars.isEmpty()) {
                    continue;
                }
                aggregators.compute(instrument, (k, existing) -> {
                    TickBarAggregator agg = existing != null ? existing : createAggregator(k);
                    synchronized (agg) {
                        if (agg.isEmpty()) {
                            agg.restore(bars);
                        }
                    }
                    return agg;
                });
                log.info("TickChart: restored {} persisted bars for {} (next seq {})",
                         bars.size(), instrument, bars.get(bars.size() - 1).seq() + 1);
            } catch (Exception e) {
                log.warn("TickChart: failed to restore persisted bars for {}: {}", instrument, e.getMessage());
            }
        }
    }

    /** Flushes completed bars to storage off the EReader thread. */
    @Scheduled(fixedDelayString = "${riskdesk.order-flow.tick-chart.persistence.flush-interval-ms:2000}",
               initialDelay = 5_000)
    public void flushPending() {
        if (!persistenceEnabled() || pendingPersist.isEmpty()) {
            return;
        }
        List<TickBar> batch = new ArrayList<>();
        TickBar bar;
        while ((bar = pendingPersist.poll()) != null) {
            batch.add(bar);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            store.saveCompleted(batch);
        } catch (Exception e) {
            log.warn("TickChart: failed to persist {} completed bars: {}", batch.size(), e.getMessage());
        }
    }

    /** Daily retention purge of persisted bars older than the configured window. */
    @Scheduled(cron = "${riskdesk.order-flow.tick-chart.persistence.purge-cron:0 23 3 * * *}")
    public void purgeExpired() {
        if (!persistenceEnabled()) {
            return;
        }
        int days = properties.getTickChart().getPersistence().getRetentionDays();
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        try {
            int deleted = store.purgeOlderThan(cutoff);
            if (deleted > 0) {
                log.info("TickChart: purged {} persisted bars older than {} ({}d retention)", deleted, cutoff, days);
            }
        } catch (Exception e) {
            log.warn("TickChart: failed to purge expired persisted bars: {}", e.getMessage());
        }
    }

    private TickBarAggregator createAggregator(Instrument instrument) {
        int ticksPerBar = ticksPerBarFor(instrument);
        int maxBars = properties.getTickChart().getMaxBars();
        log.info("TickChart: created aggregator for {} (ticksPerBar={}, maxBars={})",
                 instrument, ticksPerBar, maxBars);
        return new TickBarAggregator(instrument, ticksPerBar, maxBars);
    }

    private boolean persistenceEnabled() {
        return properties.getTickChart().getPersistence().isEnabled();
    }

    private int ticksPerBarFor(Instrument instrument) {
        Integer configured = properties.getTickChart().getTicksPerBar().get(instrument.name());
        return configured != null && configured > 0
            ? configured
            : properties.getTickChart().getDefaultTicksPerBar();
    }
}
