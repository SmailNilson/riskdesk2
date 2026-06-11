package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.orderflow.model.BigPrint;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Optional;

/**
 * Flags outsized trade prints against a rolling distribution of print sizes (UC-OF-BIGPRINT).
 *
 * <p>Maintains a rolling {@code windowMinutes} (default 30) distribution of AllLast print
 * sizes for one instrument as an incremental size histogram, and flags a print when its
 * size is at or above the configured percentile of that distribution (default p99) AND at
 * least {@code minSize} contracts (floor, default 10 — guards thin overnight tape where
 * p99 collapses to 1–2 lots). The percentile threshold is computed against the
 * <i>prior</i> distribution, excluding the print being judged, so a lone massive print
 * cannot dilute its own threshold.</p>
 *
 * <p>Every flagged print also feeds a 5-minute signed accumulator
 * ({@link #bigPrintDelta5m}) — the net aggression of large participants.</p>
 *
 * <p><b>Caveat (IBKR AllLast semantics):</b> IBKR pre-aggregates simultaneous same-price
 * executions into one AllLast print, so a "print" is a match event, not a literal order.
 * Big prints flagged here are therefore closer to <i>sweep detection</i> (one aggressive
 * order clearing several resting orders at a level) — desirable for reading institutional
 * urgency, but do not interpret a flagged print as a single resting block order.</p>
 *
 * <p>Pure domain service: caller-injected timestamps, no wall clock, no framework.
 * Methods are synchronized — ticks arrive on the IBKR EReader thread while
 * {@link #bigPrintDelta5m} is read from the 5s scheduler.</p>
 */
public class BigPrintDetector {

    /** Big-print delta accumulation window (5 minutes). */
    private static final long DELTA_WINDOW_SECONDS = 300;
    /** Histogram cap — print sizes at or above this clamp into the top bucket. */
    private static final int MAX_TRACKED_SIZE = 4096;

    private final double percentile;
    private final long minSize;
    private final long windowSeconds;

    /** Rolling distribution membership, FIFO by trade time. */
    private final ArrayDeque<SizedPrint> prints = new ArrayDeque<>();
    /** Incremental size histogram: sizeCounts[s] = prints of (clamped) size s in the window. */
    private final long[] sizeCounts = new long[MAX_TRACKED_SIZE + 1];
    private long totalPrints = 0;

    /** Flagged big prints within the delta window, FIFO by trade time. */
    private final ArrayDeque<SignedPrint> bigPrints = new ArrayDeque<>();

    public BigPrintDetector(double percentile, long minSize, int windowMinutes) {
        if (percentile <= 0.0 || percentile > 1.0) {
            throw new IllegalArgumentException("percentile must be in (0, 1]");
        }
        this.percentile = percentile;
        this.minSize = Math.max(1, minSize);
        this.windowSeconds = Math.max(1, windowMinutes) * 60L;
    }

    /**
     * Record a classified print and return it flagged when outsized.
     *
     * @param price     trade price
     * @param size      print size in contracts
     * @param side      "BUY" or "SELL" (aggressor side)
     * @param timestamp trade time (drives window eviction — never the wall clock)
     */
    public synchronized Optional<BigPrint> onPrint(double price, long size, String side,
                                                   Instant timestamp) {
        if (size <= 0) {
            return Optional.empty();
        }
        evictDistribution(timestamp);

        // Threshold from the PRIOR distribution (excluding this print).
        long threshold = Math.max(percentileSize(), minSize);
        double pct = percentileOf(size);

        prints.addLast(new SizedPrint(timestamp, size));
        sizeCounts[clamp(size)]++;
        totalPrints++;

        if (size < threshold) {
            return Optional.empty();
        }
        long signed = "BUY".equals(side) ? size : -size;
        evictBigPrints(timestamp);
        bigPrints.addLast(new SignedPrint(timestamp, signed));
        return Optional.of(new BigPrint(price, size, side, pct, timestamp));
    }

    /** Net signed volume of flagged big prints over the trailing 5 minutes as of {@code now}. */
    public synchronized long bigPrintDelta5m(Instant now) {
        evictBigPrints(now);
        long sum = 0;
        for (SignedPrint p : bigPrints) {
            sum += p.signedSize();
        }
        return sum;
    }

    /**
     * Smallest size s such that the fraction of window prints with size &le; s reaches the
     * configured percentile. 0 when the distribution is empty (the {@code minSize} floor
     * then governs alone).
     */
    private long percentileSize() {
        if (totalPrints == 0) {
            return 0;
        }
        long target = (long) Math.ceil(percentile * totalPrints);
        long cumulative = 0;
        for (int s = 0; s <= MAX_TRACKED_SIZE; s++) {
            cumulative += sizeCounts[s];
            if (cumulative >= target) {
                return s;
            }
        }
        return MAX_TRACKED_SIZE;
    }

    /** Fraction of window prints with size &le; the given size (1.0 on an empty window). */
    private double percentileOf(long size) {
        if (totalPrints == 0) {
            return 1.0;
        }
        int clamped = clamp(size);
        long cumulative = 0;
        for (int s = 0; s <= clamped; s++) {
            cumulative += sizeCounts[s];
        }
        return (double) cumulative / totalPrints;
    }

    private void evictDistribution(Instant now) {
        Instant cutoff = now.minusSeconds(windowSeconds);
        while (!prints.isEmpty() && prints.peekFirst().timestamp().isBefore(cutoff)) {
            SizedPrint removed = prints.pollFirst();
            sizeCounts[clamp(removed.size())]--;
            totalPrints--;
        }
    }

    private void evictBigPrints(Instant now) {
        Instant cutoff = now.minusSeconds(DELTA_WINDOW_SECONDS);
        while (!bigPrints.isEmpty() && bigPrints.peekFirst().timestamp().isBefore(cutoff)) {
            bigPrints.pollFirst();
        }
    }

    private static int clamp(long size) {
        return (int) Math.min(size, MAX_TRACKED_SIZE);
    }

    private record SizedPrint(Instant timestamp, long size) {}

    private record SignedPrint(Instant timestamp, long signedSize) {}
}
