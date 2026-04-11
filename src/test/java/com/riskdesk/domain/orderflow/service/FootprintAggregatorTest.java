package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.domain.orderflow.model.FootprintLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FootprintAggregatorTest {

    private FootprintAggregator aggregator;
    private final Instant now = Instant.parse("2025-04-11T14:00:00Z");

    @BeforeEach
    void setUp() {
        // MCL has tickSize 0.01
        aggregator = new FootprintAggregator(Instrument.MCL, 0.01);
    }

    @Test
    void emptyAggregator_hasNoData() {
        assertFalse(aggregator.hasData());
    }

    @Test
    void singleBuyTick_createsOneLevel() {
        aggregator.onTick(72.50, 5, "BUY", now);

        FootprintBar bar = aggregator.snapshot(now.getEpochSecond(), "5m");

        assertEquals("MCL", bar.instrument());
        assertEquals("5m", bar.timeframe());
        assertEquals(1, bar.levels().size());

        FootprintLevel level = bar.levels().values().iterator().next();
        assertEquals(5, level.buyVolume());
        assertEquals(0, level.sellVolume());
        assertEquals(5, level.delta());
        assertTrue(level.imbalance()); // 5:0 — 5 >= 3.0 threshold
    }

    @Test
    void buysAndSellsAtDifferentPrices_computesPOC() {
        aggregator.onTick(72.50, 10, "BUY", now);
        aggregator.onTick(72.50, 5, "SELL", now);
        aggregator.onTick(72.51, 20, "BUY", now);
        aggregator.onTick(72.51, 3, "SELL", now);
        aggregator.onTick(72.49, 2, "BUY", now);
        aggregator.onTick(72.49, 1, "SELL", now);

        FootprintBar bar = aggregator.snapshot(now.getEpochSecond(), "5m");

        assertEquals(3, bar.levels().size());
        // POC should be 72.51 with total volume 23 (20+3)
        assertEquals(72.51, bar.pocPrice(), 0.001);
        // Total volumes
        assertEquals(32, bar.totalBuyVolume());   // 10 + 20 + 2
        assertEquals(9, bar.totalSellVolume());    // 5 + 3 + 1
        assertEquals(23, bar.totalDelta());         // 32 - 9
    }

    @Test
    void imbalanceDetection_3to1Ratio() {
        // Level with 12 buys, 3 sells: ratio = 4:1 -> imbalance
        aggregator.onTick(72.50, 12, "BUY", now);
        aggregator.onTick(72.50, 3, "SELL", now);

        // Level with 5 buys, 5 sells: ratio = 1:1 -> no imbalance
        aggregator.onTick(72.51, 5, "BUY", now);
        aggregator.onTick(72.51, 5, "SELL", now);

        // Level with 2 buys, 8 sells: ratio = 1:4 -> imbalance (sell-side)
        aggregator.onTick(72.49, 2, "BUY", now);
        aggregator.onTick(72.49, 8, "SELL", now);

        FootprintBar bar = aggregator.snapshot(now.getEpochSecond(), "5m");

        assertTrue(bar.levels().get(72.50).imbalance(), "12:3 should be imbalance");
        assertFalse(bar.levels().get(72.51).imbalance(), "5:5 should not be imbalance");
        assertTrue(bar.levels().get(72.49).imbalance(), "2:8 should be imbalance");
    }

    @Test
    void reset_clearsAllData() {
        aggregator.onTick(72.50, 10, "BUY", now);
        assertTrue(aggregator.hasData());

        aggregator.reset();

        assertFalse(aggregator.hasData());
        FootprintBar bar = aggregator.snapshot(now.getEpochSecond(), "5m");
        assertTrue(bar.levels().isEmpty());
        assertEquals(0, bar.totalBuyVolume());
        assertEquals(0, bar.totalSellVolume());
    }

    @Test
    void priceRounding_snapsToPriceLevel() {
        // MCL tick size = 0.01
        // 72.503 / 0.01 = 7250.3, rounds to 7250, * 0.01 = 72.50
        // 72.508 / 0.01 = 7250.8, rounds to 7251, * 0.01 = 72.51
        aggregator.onTick(72.503, 3, "BUY", now);
        aggregator.onTick(72.508, 7, "BUY", now);

        FootprintBar bar = aggregator.snapshot(now.getEpochSecond(), "5m");

        assertEquals(2, bar.levels().size());
        assertNotNull(bar.levels().get(72.50), "72.503 should round to 72.50");
        assertEquals(3, bar.levels().get(72.50).buyVolume());

        // Find the 72.51 level (floating point may produce 72.51000000000001 as key)
        FootprintLevel level7251 = bar.levels().entrySet().stream()
            .filter(e -> Math.abs(e.getKey() - 72.51) < 0.001)
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
        assertNotNull(level7251, "72.508 should round to ~72.51");
        assertEquals(7, level7251.buyVolume());
    }

    @Test
    void invalidTicksAreIgnored() {
        aggregator.onTick(72.50, 0, "BUY", now);          // zero size
        aggregator.onTick(72.50, -1, "BUY", now);         // negative size
        aggregator.onTick(72.50, 5, "UNCLASSIFIED", now); // bad classification

        assertFalse(aggregator.hasData());
    }

    @Test
    void multipleTicksSameLevel_accumulate() {
        aggregator.onTick(72.50, 3, "BUY", now);
        aggregator.onTick(72.50, 7, "BUY", now);
        aggregator.onTick(72.50, 4, "SELL", now);

        FootprintBar bar = aggregator.snapshot(now.getEpochSecond(), "5m");

        assertEquals(1, bar.levels().size());
        FootprintLevel level = bar.levels().get(72.50);
        assertEquals(10, level.buyVolume());
        assertEquals(4, level.sellVolume());
        assertEquals(6, level.delta());
    }

    @Test
    void constructorRejectsZeroTickSize() {
        assertThrows(IllegalArgumentException.class,
            () -> new FootprintAggregator(Instrument.MCL, 0));
    }

    @Test
    void constructorRejectsNegativeTickSize() {
        assertThrows(IllegalArgumentException.class,
            () -> new FootprintAggregator(Instrument.MCL, -0.01));
    }

    @Test
    void snapshotIsImmutable() {
        aggregator.onTick(72.50, 5, "BUY", now);
        FootprintBar bar = aggregator.snapshot(now.getEpochSecond(), "5m");

        // Adding more ticks should not affect the already-taken snapshot
        aggregator.onTick(72.50, 100, "SELL", now);

        assertEquals(5, bar.totalBuyVolume());
        assertEquals(0, bar.totalSellVolume());
    }

    @Test
    void imbalanceWithZeroOneSide() {
        // 5 buys, 0 sells: should be imbalance (5 >= 3.0)
        aggregator.onTick(72.50, 5, "BUY", now);

        FootprintBar bar = aggregator.snapshot(now.getEpochSecond(), "5m");
        assertTrue(bar.levels().get(72.50).imbalance());
    }

    @Test
    void noImbalanceWithSmallVolumeAndZeroOtherSide() {
        // 2 buys, 0 sells: 2 < 3.0, should NOT be imbalance
        aggregator.onTick(72.50, 2, "BUY", now);

        FootprintBar bar = aggregator.snapshot(now.getEpochSecond(), "5m");
        assertFalse(bar.levels().get(72.50).imbalance());
    }
}
