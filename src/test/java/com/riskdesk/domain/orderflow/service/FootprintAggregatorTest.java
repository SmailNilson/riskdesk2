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
}
