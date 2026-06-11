package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.domain.orderflow.model.FootprintLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FootprintAggregatorTest {

    private static final int BAR_SECONDS = 600; // 10m bars

    private FootprintAggregator aggregator;
    /** 14:03:20Z — inside the 14:00–14:10 bar. */
    private final Instant now = Instant.parse("2025-04-11T14:03:20Z");
    private final long barOpen = Instant.parse("2025-04-11T14:00:00Z").getEpochSecond();

    @BeforeEach
    void setUp() {
        // MCL with a 0.05 bucket (5 ticks)
        aggregator = new FootprintAggregator(Instrument.MCL, 0.05, BAR_SECONDS);
    }

    @Test
    void emptyAggregator_hasNoData() {
        assertFalse(aggregator.hasData());
        assertTrue(aggregator.currentBar().isEmpty());
    }

    @Test
    void singleBuyTick_createsOneLevel_withClockAlignedBar() {
        assertTrue(aggregator.onTick(72.50, 5, "BUY", now).isEmpty());

        FootprintBar bar = aggregator.currentBar().orElseThrow();

        assertEquals("MCL", bar.instrument());
        assertEquals("10m", bar.timeframe());
        assertEquals(barOpen, bar.barTimestamp()); // aligned to 14:00, not the tick time
        assertEquals(1, bar.levels().size());

        FootprintLevel level = bar.levels().values().iterator().next();
        assertEquals(5, level.buyVolume());
        assertEquals(0, level.sellVolume());
        assertEquals(5, level.delta());
        assertTrue(level.imbalance()); // 5:0 — 5 >= 3.0 threshold
    }

    @Test
    void buysAndSellsAtDifferentBuckets_computesPOC() {
        aggregator.onTick(72.50, 10, "BUY", now);
        aggregator.onTick(72.50, 5, "SELL", now);
        aggregator.onTick(72.55, 20, "BUY", now);
        aggregator.onTick(72.55, 3, "SELL", now);
        aggregator.onTick(72.45, 2, "BUY", now);
        aggregator.onTick(72.45, 1, "SELL", now);

        FootprintBar bar = aggregator.currentBar().orElseThrow();

        assertEquals(3, bar.levels().size());
        // POC should be 72.55 with total volume 23 (20+3)
        assertEquals(72.55, bar.pocPrice(), 0.001);
        assertEquals(32, bar.totalBuyVolume());   // 10 + 20 + 2
        assertEquals(9, bar.totalSellVolume());   // 5 + 3 + 1
        assertEquals(23, bar.totalDelta());       // 32 - 9
    }

    @Test
    void bucketing_floorsToBucketLowerBound() {
        // 0.05 bucket: 72.51..72.54 all fall into the 72.50 bucket; 72.55 starts a new one
        aggregator.onTick(72.51, 3, "BUY", now);
        aggregator.onTick(72.54, 7, "BUY", now);
        aggregator.onTick(72.55, 2, "BUY", now);

        FootprintBar bar = aggregator.currentBar().orElseThrow();

        assertEquals(2, bar.levels().size());
        assertEquals(10, bar.levels().get(72.50).buyVolume());
        assertEquals(2, bar.levels().get(72.55).buyVolume());
    }

    @Test
    void bucketBoundary_isNotPushedDownByFloatingPoint() {
        // MNQ-style 5.0 bucket: an exact boundary price must bucket to itself
        FootprintAggregator mnq = new FootprintAggregator(Instrument.MNQ, 5.0, BAR_SECONDS);
        mnq.onTick(29005.0, 1, "BUY", now);
        mnq.onTick(29009.75, 2, "BUY", now); // same bucket [29005, 29010)
        mnq.onTick(29004.75, 4, "SELL", now); // bucket below

        FootprintBar bar = mnq.currentBar().orElseThrow();
        assertEquals(2, bar.levels().size());
        assertEquals(3, bar.levels().get(29005.0).buyVolume());
        assertEquals(4, bar.levels().get(29000.0).sellVolume());
    }

    @Test
    void tickInNextWindow_closesPreviousBar() {
        aggregator.onTick(72.50, 5, "BUY", now);

        // First tick of the 14:10–14:20 bar closes the 14:00–14:10 bar
        Instant nextWindow = Instant.parse("2025-04-11T14:10:01Z");
        Optional<FootprintBar> closed = aggregator.onTick(72.60, 2, "SELL", nextWindow);

        assertTrue(closed.isPresent());
        assertEquals(barOpen, closed.get().barTimestamp());
        assertEquals(5, closed.get().totalBuyVolume());
        assertEquals(0, closed.get().totalSellVolume());

        // The new bar contains only the new tick, aligned to 14:10
        FootprintBar current = aggregator.currentBar().orElseThrow();
        assertEquals(Instant.parse("2025-04-11T14:10:00Z").getEpochSecond(), current.barTimestamp());
        assertEquals(2, current.totalSellVolume());
        assertEquals(0, current.totalBuyVolume());
    }

    @Test
    void closeIfElapsed_closesIdleBar_onlyAfterWindowEnds() {
        aggregator.onTick(72.50, 5, "BUY", now);

        // Still inside the 14:00–14:10 window: no close
        assertTrue(aggregator.closeIfElapsed(Instant.parse("2025-04-11T14:09:59Z")).isEmpty());

        // Window elapsed with no rolling tick: idle close
        Optional<FootprintBar> closed = aggregator.closeIfElapsed(Instant.parse("2025-04-11T14:10:05Z"));
        assertTrue(closed.isPresent());
        assertEquals(barOpen, closed.get().barTimestamp());
        assertEquals(5, closed.get().totalBuyVolume());

        // Aggregator is empty afterwards
        assertFalse(aggregator.hasData());
        assertTrue(aggregator.closeIfElapsed(Instant.parse("2025-04-11T14:20:05Z")).isEmpty());
    }

    @Test
    void imbalanceDetection_3to1Ratio() {
        aggregator.onTick(72.50, 12, "BUY", now);
        aggregator.onTick(72.50, 3, "SELL", now);

        aggregator.onTick(72.55, 5, "BUY", now);
        aggregator.onTick(72.55, 5, "SELL", now);

        aggregator.onTick(72.45, 2, "BUY", now);
        aggregator.onTick(72.45, 8, "SELL", now);

        FootprintBar bar = aggregator.currentBar().orElseThrow();

        assertTrue(bar.levels().get(72.50).imbalance(), "12:3 should be imbalance");
        assertFalse(bar.levels().get(72.55).imbalance(), "5:5 should not be imbalance");
        assertTrue(bar.levels().get(72.45).imbalance(), "2:8 should be imbalance");
    }

    @Test
    void invalidTicksAreIgnored() {
        aggregator.onTick(72.50, 0, "BUY", now);          // zero size
        aggregator.onTick(72.50, -1, "BUY", now);         // negative size
        aggregator.onTick(72.50, 5, "UNCLASSIFIED", now); // bad classification

        assertFalse(aggregator.hasData());
    }

    @Test
    void multipleTicksSameBucket_accumulate() {
        aggregator.onTick(72.50, 3, "BUY", now);
        aggregator.onTick(72.50, 7, "BUY", now);
        aggregator.onTick(72.50, 4, "SELL", now);

        FootprintBar bar = aggregator.currentBar().orElseThrow();

        assertEquals(1, bar.levels().size());
        FootprintLevel level = bar.levels().get(72.50);
        assertEquals(10, level.buyVolume());
        assertEquals(4, level.sellVolume());
        assertEquals(6, level.delta());
    }

    @Test
    void constructorRejectsZeroBucketSize() {
        assertThrows(IllegalArgumentException.class,
            () -> new FootprintAggregator(Instrument.MCL, 0, BAR_SECONDS));
    }

    @Test
    void constructorRejectsNegativeBucketSize() {
        assertThrows(IllegalArgumentException.class,
            () -> new FootprintAggregator(Instrument.MCL, -0.01, BAR_SECONDS));
    }

    @Test
    void constructorRejectsNonPositiveBarSeconds() {
        assertThrows(IllegalArgumentException.class,
            () -> new FootprintAggregator(Instrument.MCL, 0.05, 0));
    }

    @Test
    void snapshotIsImmutable() {
        aggregator.onTick(72.50, 5, "BUY", now);
        FootprintBar bar = aggregator.currentBar().orElseThrow();

        aggregator.onTick(72.50, 100, "SELL", now);

        assertEquals(5, bar.totalBuyVolume());
        assertEquals(0, bar.totalSellVolume());
    }

    @Test
    void noImbalanceWithSmallVolumeAndZeroOtherSide() {
        // 2 buys, 0 sells: 2 < 3.0, should NOT be imbalance
        aggregator.onTick(72.50, 2, "BUY", now);

        FootprintBar bar = aggregator.currentBar().orElseThrow();
        assertFalse(bar.levels().get(72.50).imbalance());
    }

    @Test
    void lateTickFromPreviousWindow_attributedToCurrentBar() {
        Instant nextWindow = Instant.parse("2025-04-11T14:10:01Z");
        aggregator.onTick(72.50, 5, "BUY", nextWindow);

        // Out-of-order tick stamped in the previous (already closed) window
        Optional<FootprintBar> closed = aggregator.onTick(72.50, 3, "BUY", now);

        assertTrue(closed.isEmpty(), "late tick must not close or roll the bar");
        FootprintBar current = aggregator.currentBar().orElseThrow();
        assertEquals(8, current.totalBuyVolume());
        assertEquals(Instant.parse("2025-04-11T14:10:00Z").getEpochSecond(), current.barTimestamp());
    }

    // ─── Diagonal imbalances (industry standard) ──────────────────────────────
    // MNQ with 2.0 buckets, ratio 3.0 (300%), min cell volume 20 contracts.

    private FootprintAggregator mnqDiagonal() {
        return new FootprintAggregator(Instrument.MNQ, 2.0, BAR_SECONDS, 3.0, 20);
    }

    @Test
    void diagonalBuyImbalance_buyAtP_vsSellOneBucketLower() {
        FootprintAggregator mnq = mnqDiagonal();
        mnq.onTick(21000.0, 10, "SELL", now);  // sell at P-1 bucket
        mnq.onTick(21002.0, 30, "BUY", now);   // buy at P: 30 >= 3.0 × 10, larger cell 30 >= 20

        FootprintBar bar = mnq.currentBar().orElseThrow();
        assertTrue(bar.levels().get(21002.0).diagonalBuyImbalance(), "30 vs 10 diagonal should flag");
        assertFalse(bar.levels().get(21002.0).diagonalSellImbalance());
        assertFalse(bar.levels().get(21000.0).diagonalBuyImbalance(),
            "no sell volume one bucket below 21000 with enough size");
    }

    @Test
    void diagonalBuyImbalance_ratioNotMet_doesNotFlag() {
        FootprintAggregator mnq = mnqDiagonal();
        mnq.onTick(21000.0, 15, "SELL", now);
        mnq.onTick(21002.0, 40, "BUY", now);   // 40 < 3.0 × 15 = 45

        FootprintBar bar = mnq.currentBar().orElseThrow();
        assertFalse(bar.levels().get(21002.0).diagonalBuyImbalance());
    }

    @Test
    void diagonalImbalance_minVolumeFilter_4vs1MustNotFlag() {
        FootprintAggregator mnq = mnqDiagonal();
        mnq.onTick(21000.0, 1, "SELL", now);
        mnq.onTick(21002.0, 4, "BUY", now);    // ratio met (4 >= 3) but larger cell 4 < 20

        FootprintBar bar = mnq.currentBar().orElseThrow();
        assertFalse(bar.levels().get(21002.0).diagonalBuyImbalance(),
            "4-vs-1 must not flag with min cell volume 20");
    }

    @Test
    void diagonalSellImbalance_sellAtP_vsBuyOneBucketHigher() {
        FootprintAggregator mnq = mnqDiagonal();
        mnq.onTick(21002.0, 8, "BUY", now);    // buy at P+1 bucket
        mnq.onTick(21000.0, 25, "SELL", now);  // sell at P: 25 >= 3.0 × 8 = 24, larger 25 >= 20

        FootprintBar bar = mnq.currentBar().orElseThrow();
        assertTrue(bar.levels().get(21000.0).diagonalSellImbalance());
        assertFalse(bar.levels().get(21000.0).diagonalBuyImbalance());
    }

    @Test
    void diagonalEdges_bottomBucketBuy_topBucketSell_flagAgainstZeroNeighbour() {
        FootprintAggregator mnq = mnqDiagonal();
        // bottom bucket: 25 buys vs zero sells one bucket lower (missing bucket)
        mnq.onTick(21000.0, 25, "BUY", now);
        // top bucket: 30 sells vs zero buys one bucket higher (missing bucket)
        mnq.onTick(21004.0, 30, "SELL", now);

        FootprintBar bar = mnq.currentBar().orElseThrow();
        assertTrue(bar.levels().get(21000.0).diagonalBuyImbalance(),
            "bottom-edge buy >= min volume vs zero neighbour should flag");
        assertTrue(bar.levels().get(21004.0).diagonalSellImbalance(),
            "top-edge sell >= min volume vs zero neighbour should flag");
    }

    @Test
    void diagonalEdges_belowMinVolume_doNotFlagAgainstZeroNeighbour() {
        FootprintAggregator mnq = mnqDiagonal();
        mnq.onTick(21000.0, 19, "BUY", now);   // below the 20-contract filter

        FootprintBar bar = mnq.currentBar().orElseThrow();
        assertFalse(bar.levels().get(21000.0).diagonalBuyImbalance());
    }

    @Test
    void stackedBuyZone_threeConsecutiveFlaggedBuckets() {
        FootprintAggregator mnq = mnqDiagonal();
        // Sellers thin at every level, buyers heavy one bucket above each: 3 stacked flags
        mnq.onTick(21000.0, 5, "SELL", now);
        mnq.onTick(21002.0, 30, "BUY", now);
        mnq.onTick(21002.0, 5, "SELL", now);
        mnq.onTick(21004.0, 30, "BUY", now);
        mnq.onTick(21004.0, 5, "SELL", now);
        mnq.onTick(21006.0, 30, "BUY", now);

        FootprintBar bar = mnq.currentBar().orElseThrow();
        assertTrue(bar.levels().get(21002.0).diagonalBuyImbalance());
        assertTrue(bar.levels().get(21004.0).diagonalBuyImbalance());
        assertTrue(bar.levels().get(21006.0).diagonalBuyImbalance());

        assertEquals(1, bar.stackedBuyZones().size());
        assertEquals(21002.0, bar.stackedBuyZones().get(0).fromPrice(), 0.001);
        assertEquals(21006.0, bar.stackedBuyZones().get(0).toPrice(), 0.001);
        assertEquals(3, bar.stackedBuyZones().get(0).buckets());
        assertTrue(bar.stackedSellZones().isEmpty());
    }

    @Test
    void twoConsecutiveFlaggedBuckets_doNotFormAZone() {
        FootprintAggregator mnq = mnqDiagonal();
        mnq.onTick(21000.0, 5, "SELL", now);
        mnq.onTick(21002.0, 30, "BUY", now);
        mnq.onTick(21002.0, 5, "SELL", now);
        mnq.onTick(21004.0, 30, "BUY", now);
        // break the run: heavy sells at 21004 so 21006 cannot flag
        mnq.onTick(21004.0, 40, "SELL", now);
        mnq.onTick(21006.0, 30, "BUY", now);

        FootprintBar bar = mnq.currentBar().orElseThrow();
        assertTrue(bar.levels().get(21002.0).diagonalBuyImbalance());
        assertTrue(bar.levels().get(21004.0).diagonalBuyImbalance());
        assertFalse(bar.levels().get(21006.0).diagonalBuyImbalance(), "30 < 3 × 45 sells below");
        assertTrue(bar.stackedBuyZones().isEmpty(), "2-bucket run must not form a zone");
    }

    @Test
    void nonAdjacentFlaggedBuckets_doNotStack() {
        FootprintAggregator mnq = mnqDiagonal();
        // Three flagged buckets with a gap at 21004 (no volume there at all)
        mnq.onTick(21000.0, 25, "BUY", now);   // edge flag
        mnq.onTick(21006.0, 25, "BUY", now);   // edge flag (neighbour 21004 empty)
        mnq.onTick(21010.0, 25, "BUY", now);   // edge flag (neighbour 21008 empty)

        FootprintBar bar = mnq.currentBar().orElseThrow();
        assertTrue(bar.levels().get(21000.0).diagonalBuyImbalance());
        assertTrue(bar.levels().get(21006.0).diagonalBuyImbalance());
        assertTrue(bar.levels().get(21010.0).diagonalBuyImbalance());
        assertTrue(bar.stackedBuyZones().isEmpty(), "gapped flags must not stack");
    }

    @Test
    void unfinishedAuction_topAndBottomBuckets() {
        FootprintAggregator mnq = mnqDiagonal();
        // top bucket 21004 traded on both sides -> unfinishedHigh
        mnq.onTick(21004.0, 3, "BUY", now);
        mnq.onTick(21004.0, 2, "SELL", now);
        // bottom bucket 21000 one-sided -> finished low
        mnq.onTick(21000.0, 5, "SELL", now);

        FootprintBar bar = mnq.currentBar().orElseThrow();
        assertTrue(bar.unfinishedHigh(), "both sides traded at the high");
        assertFalse(bar.unfinishedLow(), "only sells traded at the low");
    }

    @Test
    void unfinishedAuction_lowFlagMirrorsBottomBucket() {
        FootprintAggregator mnq = mnqDiagonal();
        mnq.onTick(21000.0, 3, "BUY", now);
        mnq.onTick(21000.0, 2, "SELL", now);
        mnq.onTick(21004.0, 5, "BUY", now);

        FootprintBar bar = mnq.currentBar().orElseThrow();
        assertTrue(bar.unfinishedLow());
        assertFalse(bar.unfinishedHigh());
    }

    @Test
    void constructorRejectsImbalanceRatioBelowOne() {
        assertThrows(IllegalArgumentException.class,
            () -> new FootprintAggregator(Instrument.MNQ, 2.0, BAR_SECONDS, 1.0, 20));
    }
}
