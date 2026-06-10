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
import java.util.Collections;
import java.util.List;
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

    // Wall state tracking
    private final boolean[] bidWasWall = new boolean[MAX_DEPTH];
    private final boolean[] askWasWall = new boolean[MAX_DEPTH];
    private final ConcurrentLinkedDeque<WallEvent> wallEvents = new ConcurrentLinkedDeque<>();

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
    public void updateDepth(int position, String operation, String side,
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

        checkWallSide(bidPrices, bidSizes, bidCount, bidWasWall,
                      WallEvent.WallSide.BID, instrument, now);
        checkWallSide(askPrices, askSizes, askCount, askWasWall,
                      WallEvent.WallSide.ASK, instrument, now);

        // Bound deque size
        while (wallEvents.size() > MAX_WALL_EVENTS) {
            wallEvents.pollFirst();
        }
    }

    private void checkWallSide(double[] prices, long[] sizes, int count,
                               boolean[] wasWall, WallEvent.WallSide side,
                               Instrument instrument, Instant now) {
        if (count == 0) {
            // Clear all wall states when no levels present
            for (int i = 0; i < MAX_DEPTH; i++) {
                wasWall[i] = false;
            }
            return;
        }

        long total = 0;
        for (int i = 0; i < count; i++) {
            total += sizes[i];
        }
        double avg = (double) total / count;
        double threshold = wallThresholdMultiplier * avg;

        for (int i = 0; i < count; i++) {
            boolean isWallNow = avg > 0 && sizes[i] > threshold;
            if (isWallNow && !wasWall[i]) {
                wallEvents.addLast(new WallEvent(
                    instrument, side, prices[i], sizes[i], now,
                    WallEvent.WallEventType.APPEARED
                ));
                wasWall[i] = true;
            } else if (!isWallNow && wasWall[i]) {
                wallEvents.addLast(new WallEvent(
                    instrument, side, prices[i], sizes[i], now,
                    WallEvent.WallEventType.DISAPPEARED
                ));
                wasWall[i] = false;
            }
        }

        // Clear wall state for levels beyond current count
        for (int i = count; i < MAX_DEPTH; i++) {
            wasWall[i] = false;
        }
    }

    public boolean hasData() {
        return bidCount > 0 || askCount > 0;
    }
}
