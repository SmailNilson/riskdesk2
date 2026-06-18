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

    /** Per instrument, one aggregator per bar size (the base plus each configured coarse
     *  size), as a small array aligned with {@link #sizesFor(Instrument)}. Large sizes get
     *  their own small ring buffer so deep history is available without keeping ~15k base
     *  bars for client-side merging. An array (not a per-key map) keeps {@code onTick} —
     *  the per-trade EReader hot path — allocation-free: no list build, no key boxing. */
    private final ConcurrentHashMap<Instrument, TickBarAggregator[]> aggregators = new ConcurrentHashMap<>();
    /** Per-instrument bar sizes, computed once from static config (base + valid coarse). */
    private final ConcurrentHashMap<Instrument, int[]> sizesCache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TickBar> pendingPersist = new ConcurrentLinkedQueue<>();
    private final OrderFlowProperties properties;
    private final TickBarStorePort store;

    public IbkrTickBarAdapter(OrderFlowProperties properties, TickBarStorePort store) {
        this.properties = properties;
        this.store = store;
    }

    /**
     * Routes a classified trade to every tick-bar aggregator for the instrument (base
     * size + each configured coarse size). Bars that close on this trade are queued for
     * durable persistence.
     */
    public void onTick(Instrument instrument, double price, long size,
                       String classification, Instant timestamp) {
        TickBarAggregator[] aggs = aggregators.computeIfAbsent(instrument, this::createAggregatorsFor);
        boolean persist = persistenceEnabled();
        for (TickBarAggregator aggregator : aggs) {
            Optional<TickBar> completed;
            synchronized (aggregator) {
                completed = aggregator.onTick(price, size, classification, timestamp);
            }
            if (persist && completed.isPresent()) {
                pendingPersist.add(completed.get());
            }
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
            log.info("TickChart: cleared tick-bar aggregators for {} on contract rollover", instrument);
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
        return recentBars(instrument, ticksPerBarFor(instrument), limit);
    }

    @Override
    public List<TickBar> recentBars(Instrument instrument, int ticksPerBar, int limit) {
        TickBarAggregator aggregator = existingAggregator(instrument, ticksPerBar);
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
        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            for (int ticksPerBar : sizesFor(instrument)) {
                try {
                    List<TickBar> bars = store.loadRecent(instrument, ticksPerBar, maxBarsFor(instrument, ticksPerBar));
                    if (bars.isEmpty()) {
                        continue;
                    }
                    // Create the instrument's aggregator set lazily only when there is
                    // something to restore (degraded instruments with no ticks stay absent).
                    TickBarAggregator agg = aggregatorFor(instrument, ticksPerBar);
                    if (agg == null) {
                        continue;
                    }
                    synchronized (agg) {
                        if (agg.isEmpty()) {
                            agg.restore(bars);
                        }
                    }
                    log.info("TickChart: restored {} persisted bars for {} @ {} ticks (next seq {})",
                             bars.size(), instrument, ticksPerBar, bars.get(bars.size() - 1).seq() + 1);
                } catch (Exception e) {
                    log.warn("TickChart: failed to restore persisted bars for {} @ {} ticks: {}",
                             instrument, ticksPerBar, e.getMessage());
                }
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

    /** Builds the full aggregator set for an instrument, aligned with {@link #sizesFor}. */
    private TickBarAggregator[] createAggregatorsFor(Instrument instrument) {
        int[] sizes = sizesFor(instrument);
        TickBarAggregator[] aggs = new TickBarAggregator[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            aggs[i] = createAggregator(instrument, sizes[i]);
        }
        return aggs;
    }

    private TickBarAggregator createAggregator(Instrument instrument, int ticksPerBar) {
        int maxBars = maxBarsFor(instrument, ticksPerBar);
        log.info("TickChart: created aggregator for {} (ticksPerBar={}, maxBars={})",
                 instrument, ticksPerBar, maxBars);
        return new TickBarAggregator(instrument, ticksPerBar, maxBars);
    }

    /** The aggregator for a size, creating the instrument's set if needed; null if the size isn't maintained. */
    private TickBarAggregator aggregatorFor(Instrument instrument, int ticksPerBar) {
        return find(aggregators.computeIfAbsent(instrument, this::createAggregatorsFor), ticksPerBar);
    }

    /** The aggregator for a size without creating anything (read path); null if absent. */
    private TickBarAggregator existingAggregator(Instrument instrument, int ticksPerBar) {
        return find(aggregators.get(instrument), ticksPerBar);
    }

    private static TickBarAggregator find(TickBarAggregator[] aggs, int ticksPerBar) {
        if (aggs == null) {
            return null;
        }
        for (TickBarAggregator a : aggs) {
            if (a.ticksPerBar() == ticksPerBar) {
                return a;
            }
        }
        return null;
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

    /**
     * Bar sizes to maintain for an instrument: the per-instrument base, plus each
     * configured coarse size that is a strict multiple of the base (so a coarse bar
     * lines up with a whole number of base bars) — the same multiple guard the client
     * applies before offering a size button. Computed once per instrument and cached,
     * since it derives only from static config and is read on the per-trade hot path.
     */
    private int[] sizesFor(Instrument instrument) {
        return sizesCache.computeIfAbsent(instrument, this::computeSizes);
    }

    private int[] computeSizes(Instrument instrument) {
        int base = ticksPerBarFor(instrument);
        List<Integer> sizes = new ArrayList<>();
        sizes.add(base);
        for (Integer coarse : properties.getTickChart().getCoarseSizes()) {
            if (coarse != null && coarse > base && coarse % base == 0) {
                sizes.add(coarse);
            }
        }
        return sizes.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Ring depth: full {@code maxBars} for the base size, the smaller coarse depth otherwise. */
    private int maxBarsFor(Instrument instrument, int ticksPerBar) {
        return ticksPerBar == ticksPerBarFor(instrument)
            ? properties.getTickChart().getMaxBars()
            : properties.getTickChart().getCoarseMaxBars();
    }
}
