package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.TickBar;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Pure domain service that aggregates classified trades into constant-tick-count
 * bars (tick chart). NOT a Spring bean — instantiated per instrument by the
 * infrastructure adapter.
 *
 * <p>Completed bars are kept in a bounded ring buffer (oldest evicted); the
 * in-progress bar is exposed separately with {@code complete=false}.</p>
 *
 * <p>Thread safety: external callers must synchronize if used from multiple threads.</p>
 */
public class TickBarAggregator {

    private final Instrument instrument;
    private final int ticksPerBar;
    private final int maxBars;

    private final Deque<TickBar> completedBars = new ArrayDeque<>();
    private long nextSeq = 0;

    // In-progress bar state
    private int tickCount;
    private long openTime;
    private long closeTime;
    private double open;
    private double high;
    private double low;
    private double close;
    private long buyVolume;
    private long sellVolume;

    public TickBarAggregator(Instrument instrument, int ticksPerBar, int maxBars) {
        if (ticksPerBar <= 0) {
            throw new IllegalArgumentException("ticksPerBar must be positive, got: " + ticksPerBar);
        }
        if (maxBars <= 0) {
            throw new IllegalArgumentException("maxBars must be positive, got: " + maxBars);
        }
        this.instrument = instrument;
        this.ticksPerBar = ticksPerBar;
        this.maxBars = maxBars;
    }

    /**
     * Records a classified trade. When this trade is the bar's Nth tick, the bar
     * completes and is returned (also retained in the ring buffer).
     *
     * @param price          trade price
     * @param size           number of contracts
     * @param classification "BUY" or "SELL" (anything else is ignored)
     * @param timestamp      the trade timestamp
     * @return the completed bar when this trade closed it; empty otherwise
     */
    public Optional<TickBar> onTick(double price, long size, String classification, Instant timestamp) {
        if (size <= 0) return Optional.empty();
        boolean buy = "BUY".equals(classification);
        if (!buy && !"SELL".equals(classification)) return Optional.empty();

        long epochSec = timestamp.getEpochSecond();
        if (tickCount == 0) {
            openTime = epochSec;
            open = price;
            high = price;
            low = price;
        }
        closeTime = epochSec;
        close = price;
        high = Math.max(high, price);
        low = Math.min(low, price);
        if (buy) buyVolume += size; else sellVolume += size;
        tickCount++;

        if (tickCount < ticksPerBar) {
            return Optional.empty();
        }

        TickBar bar = snapshot(true);
        completedBars.addLast(bar);
        while (completedBars.size() > maxBars) {
            completedBars.pollFirst();
        }
        resetCurrent();
        return Optional.of(bar);
    }

    /**
     * The most recent bars, oldest first: up to {@code limit - 1} completed bars
     * followed by the in-progress bar (if it has any trades).
     */
    public List<TickBar> recentBars(int limit) {
        int capped = Math.max(1, limit);
        Optional<TickBar> current = currentBar();
        int completedWanted = capped - (current.isPresent() ? 1 : 0);

        List<TickBar> all = new ArrayList<>(completedBars);
        int from = Math.max(0, all.size() - completedWanted);
        List<TickBar> out = new ArrayList<>(all.subList(from, all.size()));
        current.ifPresent(out::add);
        return out;
    }

    /** The in-progress bar ({@code complete=false}), or empty when no trade yet. */
    public Optional<TickBar> currentBar() {
        if (tickCount == 0) return Optional.empty();
        return Optional.of(snapshot(false));
    }

    private TickBar snapshot(boolean complete) {
        // The in-progress bar carries seq == nextSeq and keeps it on completion,
        // so WS consumers can merge partial updates and the final bar by seq.
        return new TickBar(
            instrument.name(), ticksPerBar, nextSeq,
            openTime, closeTime, open, high, low, close,
            buyVolume + sellVolume, buyVolume, sellVolume,
            buyVolume - sellVolume, tickCount, complete
        );
    }

    private void resetCurrent() {
        tickCount = 0;
        buyVolume = 0;
        sellVolume = 0;
        nextSeq++;
    }
}
