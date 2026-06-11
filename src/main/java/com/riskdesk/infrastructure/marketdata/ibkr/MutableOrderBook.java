package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DepthLevel;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.model.WallEvent;
import com.riskdesk.domain.orderflow.model.WallInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Pre-allocated mutable order book for memory-efficient market depth tracking.
 * NOT a Spring bean — instantiated per instrument by IbkrMarketDepthAdapter.
 *
 * <p>Uses a seqlock pattern (volatile generation counter) for thread-safe reads:
 * the writer increments generation before and after mutation; readers retry if
 * the generation changed or was odd during their snapshot.</p>
 *
 * <p>Wall detection runs after every depth mutation. A "wall" is a single price
 * level with size > wallThreshold * average level size. WallEvents are bounded
 * at 200 entries (oldest evicted).</p>
 */
public class MutableOrderBook {

    private static final Logger log = LoggerFactory.getLogger(MutableOrderBook.class);

    public static final int MAX_DEPTH = 10;
    private static final int MAX_WALL_EVENTS = 200;
    private static final int MAX_SEQLOCK_RETRIES = 3;

    // Pre-allocated arrays — mutated in-place
    private final double[] bidPrices = new double[MAX_DEPTH];
    private final long[] bidSizes = new long[MAX_DEPTH];
    private final double[] askPrices = new double[MAX_DEPTH];
    private final long[] askSizes = new long[MAX_DEPTH];

    private int bidCount;
    private int askCount;

    // Seqlock generation counter — odd means write in progress
    private volatile int generation;

    // Wall-clock millis of the last real depth mutation from IBKR. Drives staleness
    // detection: a silently frozen L2 feed stops bumping this even though the arrays
    // still hold (stale) levels. Initialised to construction time so a never-updated
    // book reads as "just born" rather than 1970.
    private volatile long lastUpdateMillis = System.currentTimeMillis();

    // Wall state tracking — keyed by tick-rounded PRICE, not array index. Index-keyed
    // flags flicker on every book shift: an INSERT/DELETE moves the same resting order
    // to a new index, emitting spurious APPEARED/DISAPPEARED pairs at neighbouring
    // prices (the source of duplicate spoof signals and 0.0s "icebergs"). Only the
    // writer thread mutates these maps.
    private final Map<Long, TrackedWall> bidWalls = new HashMap<>();
    private final Map<Long, TrackedWall> askWalls = new HashMap<>();
    private final ConcurrentLinkedDeque<WallEvent> wallEvents = new ConcurrentLinkedDeque<>();

    /** Last known price/size of a tracked wall — so DISAPPEARED carries the wall's own values. */
    private static final class TrackedWall {
        double price;
        long lastSize;
    }

    private final double wallThresholdMultiplier;

    public MutableOrderBook(double wallThresholdMultiplier) {
        this.wallThresholdMultiplier = wallThresholdMultiplier;
    }

    /**
     * Mutates the order book in-place from an IBKR depth callback.
     *
     * @param position  level index (0-based, max MAX_DEPTH-1)
     * @param operation "INSERT", "UPDATE", or "DELETE"
     * @param side      "BUY" (bid) or "SELL" (ask)
     * @param price     price at this level
     * @param size      size at this level
     * @param instrument the instrument (for wall event creation)
     */
    public synchronized void updateDepth(int position, String operation, String side,
                            double price, long size, Instrument instrument) {
        if (position < 0 || position >= MAX_DEPTH) {
            return;
        }

        boolean isBid = "BUY".equals(side);

        // Record arrival of real depth data (used by the freshness watchdog).
        lastUpdateMillis = System.currentTimeMillis();

        // Begin write: increment to odd
        generation++;

        try {
            if (isBid) {
                applyMutation(bidPrices, bidSizes, position, operation, price, size, true);
            } else {
                applyMutation(askPrices, askSizes, position, operation, price, size, false);
            }
        } finally {
            // End write: increment to even
            generation++;
        }

        checkWalls(instrument);
    }

    /**
     * Empties the book. Must be called before a (re)subscription so the fresh IBKR
     * snapshot (INSERTs at positions 0..N-1) never merges into levels from a previous
     * price regime or contract — without this, a thin fresh snapshot leaves phantom
     * deep levels pinned forever (the "25-point spread" frozen-ladder symptom).
     * Wall events are retained: they are timestamped history and clearing them
     * mid-window would blind the spoofing detector to a wall pulled just before
     * the resubscription.
     */
    public synchronized void clear() {
        generation++;
        try {
            Arrays.fill(bidPrices, 0);
            Arrays.fill(bidSizes, 0);
            Arrays.fill(askPrices, 0);
            Arrays.fill(askSizes, 0);
            bidCount = 0;
            askCount = 0;
        } finally {
            generation++;
        }
        bidWalls.clear();
        askWalls.clear();
    }

    private void applyMutation(double[] prices, long[] sizes, int position,
                               String operation, double price, long size, boolean isBid) {
        switch (operation) {
            case "INSERT" -> {
                int count = isBid ? bidCount : askCount;
                int newCount = Math.min(count + 1, MAX_DEPTH);
                // Shift elements down to make room at position
                for (int i = newCount - 1; i > position; i--) {
                    prices[i] = prices[i - 1];
                    sizes[i] = sizes[i - 1];
                }
                prices[position] = price;
                sizes[position] = size;
                if (isBid) {
                    bidCount = newCount;
                } else {
                    askCount = newCount;
                }
            }
            case "UPDATE" -> {
                int count = isBid ? bidCount : askCount;
                if (position < count) {
                    prices[position] = price;
                    sizes[position] = size;
                }
            }
            case "DELETE" -> {
                int count = isBid ? bidCount : askCount;
                if (position < count) {
                    // Shift elements up to fill the gap
                    for (int i = position; i < count - 1; i++) {
                        prices[i] = prices[i + 1];
                        sizes[i] = sizes[i + 1];
                    }
                    int newCount = count - 1;
                    // Zero out the freed slot
                    prices[newCount] = 0;
                    sizes[newCount] = 0;
                    if (isBid) {
                        bidCount = newCount;
                    } else {
                        askCount = newCount;
                    }
                }
            }
            default -> { /* unknown operation, ignore */ }
        }
    }

    /**
     * On-demand depth metrics using seqlock for thread-safe reads.
     * Retries up to MAX_SEQLOCK_RETRIES times if a concurrent write is detected.
     */
    public DepthMetrics computeMetrics(Instrument instrument) {
        for (int attempt = 0; attempt < MAX_SEQLOCK_RETRIES; attempt++) {
            int genBefore = generation;
            // If odd, write in progress — spin briefly
            if ((genBefore & 1) != 0) {
                Thread.yield();
                continue;
            }

            // Snapshot arrays
            long totalBidSize = 0;
            long totalAskSize = 0;
            int bc = bidCount;
            int ac = askCount;

            double bestBid = bc > 0 ? bidPrices[0] : 0;
            double bestAsk = ac > 0 ? askPrices[0] : 0;

            // Copy the ladders locally so the seqlock check below validates them too.
            double[] bidPx = new double[bc];
            long[] bidSz = new long[bc];
            double[] askPx = new double[ac];
            long[] askSz = new long[ac];
            System.arraycopy(bidPrices, 0, bidPx, 0, bc);
            System.arraycopy(bidSizes, 0, bidSz, 0, bc);
            System.arraycopy(askPrices, 0, askPx, 0, ac);
            System.arraycopy(askSizes, 0, askSz, 0, ac);

            long maxBidSize = 0;
            double maxBidPrice = 0;
            int maxBidLevel = -1;

            long maxAskSize = 0;
            double maxAskPrice = 0;
            int maxAskLevel = -1;

            for (int i = 0; i < bc; i++) {
                totalBidSize += bidSizes[i];
                if (bidSizes[i] > maxBidSize) {
                    maxBidSize = bidSizes[i];
                    maxBidPrice = bidPrices[i];
                    maxBidLevel = i;
                }
            }
            for (int i = 0; i < ac; i++) {
                totalAskSize += askSizes[i];
                if (askSizes[i] > maxAskSize) {
                    maxAskSize = askSizes[i];
                    maxAskPrice = askPrices[i];
                    maxAskLevel = i;
                }
            }

            int genAfter = generation;
            if (genBefore != genAfter) {
                // Generation changed during read — retry
                continue;
            }

            // Compute derived metrics
            double depthImbalance;
            if (totalBidSize == 0 && totalAskSize == 0) {
                depthImbalance = 0;
            } else {
                depthImbalance = (double) (totalBidSize - totalAskSize) / (totalBidSize + totalAskSize);
            }

            double spread = (bestAsk > 0 && bestBid > 0) ? bestAsk - bestBid : 0;
            double tickSize = instrument.getTickSize().doubleValue();
            double spreadTicks = tickSize > 0 ? spread / tickSize : 0;

            // Wall detection in metrics: check if the largest level exceeds threshold
            double avgBidSize = bc > 0 ? (double) totalBidSize / bc : 0;
            double avgAskSize = ac > 0 ? (double) totalAskSize / ac : 0;

            WallInfo bidWall = null;
            if (maxBidLevel >= 0 && avgBidSize > 0 && maxBidSize > wallThresholdMultiplier * avgBidSize) {
                bidWall = new WallInfo(maxBidPrice, maxBidSize, maxBidLevel);
            }

            WallInfo askWall = null;
            if (maxAskLevel >= 0 && avgAskSize > 0 && maxAskSize > wallThresholdMultiplier * avgAskSize) {
                askWall = new WallInfo(maxAskPrice, maxAskSize, maxAskLevel);
            }

            return new DepthMetrics(
                instrument, totalBidSize, totalAskSize, depthImbalance,
                bestBid, bestAsk, spread, spreadTicks,
                bidWall, askWall,
                buildLadder(bidPx, bidSz, avgBidSize),
                buildLadder(askPx, askSz, avgAskSize),
                Instant.ofEpochMilli(lastUpdateMillis)
            );
        }

        log.warn("Seqlock retries exhausted for {} — returning zero-value metrics", instrument);
        return new DepthMetrics(
            instrument, 0, 0, 0, 0, 0, 0, 0,
            null, null, List.of(), List.of(), Instant.ofEpochMilli(lastUpdateMillis)
        );
    }

    /** Builds an immutable best-first ladder, flagging levels above the wall threshold. */
    private List<DepthLevel> buildLadder(double[] prices, long[] sizes, double avgSize) {
        if (prices.length == 0) return List.of();
        double threshold = wallThresholdMultiplier * avgSize;
        List<DepthLevel> ladder = new ArrayList<>(prices.length);
        for (int i = 0; i < prices.length; i++) {
            boolean wall = avgSize > 0 && sizes[i] > threshold;
            ladder.add(new DepthLevel(prices[i], sizes[i], wall));
        }
        return Collections.unmodifiableList(ladder);
    }

    /**
     * Returns wall events within the specified lookback period, in chronological order.
     */
    public List<WallEvent> recentWallEvents(Duration lookback) {
        Instant cutoff = Instant.now().minus(lookback);
        List<WallEvent> result = new ArrayList<>();
        var it = wallEvents.descendingIterator();
        while (it.hasNext()) {
            WallEvent event = it.next();
            if (event.timestamp().isBefore(cutoff)) {
                break;
            }
            result.add(event);
        }
        Collections.reverse(result);
        return result;
    }

    /**
     * Checks each level for wall state transitions and emits WallEvents.
     */
    private void checkWalls(Instrument instrument) {
        Instant now = Instant.now();
        double tickSize = instrument.getTickSize().doubleValue();

        checkWallSide(bidPrices, bidSizes, bidCount, bidWalls,
                      WallEvent.WallSide.BID, instrument, tickSize, now);
        checkWallSide(askPrices, askSizes, askCount, askWalls,
                      WallEvent.WallSide.ASK, instrument, tickSize, now);

        // Bound deque size
        while (wallEvents.size() > MAX_WALL_EVENTS) {
            wallEvents.pollFirst();
        }
    }

    private void checkWallSide(double[] prices, long[] sizes, int count,
                               Map<Long, TrackedWall> tracked, WallEvent.WallSide side,
                               Instrument instrument, double tickSize, Instant now) {
        if (count == 0) {
            // Side emptied: every tracked wall is gone — emit with the wall's OWN values.
            emitAllDisappeared(tracked, side, instrument, now);
            return;
        }

        long total = 0;
        for (int i = 0; i < count; i++) {
            total += sizes[i];
        }
        double avg = (double) total / count;
        double threshold = wallThresholdMultiplier * avg;

        Set<Long> stillWalled = new HashSet<>(4);
        for (int i = 0; i < count; i++) {
            if (!(avg > 0 && sizes[i] > threshold)) continue;
            long key = Math.round(prices[i] / tickSize);
            stillWalled.add(key);
            TrackedWall wall = tracked.get(key);
            if (wall == null) {
                wall = new TrackedWall();
                wall.price = prices[i];
                wall.lastSize = sizes[i];
                tracked.put(key, wall);
                wallEvents.addLast(new WallEvent(
                    instrument, side, prices[i], sizes[i], now,
                    WallEvent.WallEventType.APPEARED
                ));
            } else {
                // Same price still walled (possibly at a new index after a shift) —
                // refresh size, no event: this is the flicker the old index-keyed
                // flags converted into APPEARED/DISAPPEARED noise.
                wall.lastSize = sizes[i];
            }
        }

        Iterator<Map.Entry<Long, TrackedWall>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, TrackedWall> entry = it.next();
            if (stillWalled.contains(entry.getKey())) continue;
            TrackedWall wall = entry.getValue();
            wallEvents.addLast(new WallEvent(
                instrument, side, wall.price, wall.lastSize, now,
                WallEvent.WallEventType.DISAPPEARED
            ));
            it.remove();
        }
    }

    private void emitAllDisappeared(Map<Long, TrackedWall> tracked, WallEvent.WallSide side,
                                    Instrument instrument, Instant now) {
        if (tracked.isEmpty()) return;
        for (TrackedWall wall : tracked.values()) {
            wallEvents.addLast(new WallEvent(
                instrument, side, wall.price, wall.lastSize, now,
                WallEvent.WallEventType.DISAPPEARED
            ));
        }
        tracked.clear();
    }

    public boolean hasData() {
        return bidCount > 0 || askCount > 0;
    }
}
