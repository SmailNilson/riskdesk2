package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.riskdesk.infrastructure.marketdata.ibkr.TickByTickAggregator.TickClassification.*;
import static org.junit.jupiter.api.Assertions.*;

class TickByTickAggregatorTest {

    @Test
    void emptyAggregatorReturnsFlat() {
        var agg = new TickByTickAggregator(Instrument.MCL);
        var snapshot = agg.snapshot();
        assertEquals(0, snapshot.buyVolume());
        assertEquals(0, snapshot.sellVolume());
        assertEquals("FLAT", snapshot.deltaTrend());
        assertFalse(snapshot.divergenceDetected());
        assertEquals("REAL_TICKS", snapshot.source());
    }

    @Test
    void allBuysPositiveDelta() {
        var agg = new TickByTickAggregator(Instrument.MCL);
        var now = Instant.now();
        agg.onTick(100.0, 10, BUY, now);
        agg.onTick(100.5, 20, BUY, now.plusSeconds(1));
        agg.onTick(101.0, 15, BUY, now.plusSeconds(2));

        var snapshot = agg.snapshot();
        assertEquals(45, snapshot.buyVolume());
        assertEquals(0, snapshot.sellVolume());
        assertEquals(45, snapshot.delta());
        assertEquals(100.0, snapshot.buyRatioPct(), 0.1);
    }

    @Test
    void allSellsNegativeDelta() {
        var agg = new TickByTickAggregator(Instrument.MGC);
        var now = Instant.now();
        agg.onTick(2000.0, 5, SELL, now);
        agg.onTick(1999.0, 10, SELL, now.plusSeconds(1));

        var snapshot = agg.snapshot();
        assertEquals(0, snapshot.buyVolume());
        assertEquals(15, snapshot.sellVolume());
        assertEquals(-15, snapshot.delta());
        assertEquals(0.0, snapshot.buyRatioPct(), 0.1);
    }

    @Test
    void mixedTradesCorrectRatio() {
        var agg = new TickByTickAggregator(Instrument.MNQ);
        var now = Instant.now();
        agg.onTick(100.0, 60, BUY, now);
        agg.onTick(99.0, 40, SELL, now.plusSeconds(1));

        var snapshot = agg.snapshot();
        assertEquals(60, snapshot.buyVolume());
        assertEquals(40, snapshot.sellVolume());
        assertEquals(20, snapshot.delta());
        assertEquals(60.0, snapshot.buyRatioPct(), 0.1);
    }

    @Test
    void bearishDivergence_priceUpDeltaDown() {
        var agg = new TickByTickAggregator(Instrument.MCL);
        var now = Instant.now();
        // Price going up but more sells
        agg.onTick(100.0, 5, BUY, now);
        agg.onTick(101.0, 20, SELL, now.plusSeconds(1));
        agg.onTick(102.0, 10, SELL, now.plusSeconds(2));

        var snapshot = agg.snapshot();
        assertTrue(snapshot.divergenceDetected());
        assertEquals("BEARISH_DIVERGENCE", snapshot.divergenceType());
    }

    @Test
    void bullishDivergence_priceDownDeltaUp() {
        var agg = new TickByTickAggregator(Instrument.MGC);
        var now = Instant.now();
        // Price going down but more buys
        agg.onTick(2000.0, 20, BUY, now);
        agg.onTick(1999.0, 5, SELL, now.plusSeconds(1));
        agg.onTick(1998.0, 10, BUY, now.plusSeconds(2));

        var snapshot = agg.snapshot();
        assertTrue(snapshot.divergenceDetected());
        assertEquals("BULLISH_DIVERGENCE", snapshot.divergenceType());
    }

    @Test
    void windowEvictionRemovesOldTicks() {
        var agg = new TickByTickAggregator(Instrument.MCL, 10); // 10 second window
        var base = Instant.now().minusSeconds(20);

        // Old ticks (will be evicted)
        agg.onTick(100.0, 100, BUY, base);
        // Recent ticks
        agg.onTick(101.0, 5, SELL, Instant.now());

        var snapshot = agg.snapshot();
        // Old 100-volume BUY should be evicted
        assertEquals(0, snapshot.buyVolume());
        assertEquals(5, snapshot.sellVolume());
    }

    @Test
    void unclassifiedTicksIgnored() {
        var agg = new TickByTickAggregator(Instrument.E6);
        var now = Instant.now();
        agg.onTick(1.05, 10, UNCLASSIFIED, now);

        assertFalse(agg.hasData());
    }

    @Test
    void highLowPriceTracking() {
        var agg = new TickByTickAggregator(Instrument.MCL);
        var now = Instant.now();
        agg.onTick(100.0, 5, BUY, now);
        agg.onTick(102.5, 10, SELL, now.plusSeconds(1));
        agg.onTick(99.0, 8, BUY, now.plusSeconds(2));
        agg.onTick(101.0, 3, SELL, now.plusSeconds(3));

        var snapshot = agg.snapshot();
        assertEquals(102.5, snapshot.highPrice(), 0.001);
        assertEquals(99.0, snapshot.lowPrice(), 0.001);
    }

    @Test
    void emptyAggregatorReturnsNaNPrices() {
        var agg = new TickByTickAggregator(Instrument.MCL);
        var snapshot = agg.snapshot();
        assertTrue(Double.isNaN(snapshot.highPrice()));
        assertTrue(Double.isNaN(snapshot.lowPrice()));
    }

    @Test
    void leeReadyClassification() {
        // Trade at ask = BUY
        assertEquals(BUY, IbkrTickDataAdapter.classifyTrade(100.05, 100.00, 100.05));
        // Trade above ask = BUY
        assertEquals(BUY, IbkrTickDataAdapter.classifyTrade(100.10, 100.00, 100.05));
        // Trade at bid = SELL
        assertEquals(SELL, IbkrTickDataAdapter.classifyTrade(100.00, 100.00, 100.05));
        // Trade below bid = SELL
        assertEquals(SELL, IbkrTickDataAdapter.classifyTrade(99.95, 100.00, 100.05));
        // Trade above midpoint = BUY
        assertEquals(BUY, IbkrTickDataAdapter.classifyTrade(100.03, 100.00, 100.05));
        // Trade below midpoint = SELL
        assertEquals(SELL, IbkrTickDataAdapter.classifyTrade(100.02, 100.00, 100.05));
        // Invalid quotes = UNCLASSIFIED
        assertEquals(UNCLASSIFIED, IbkrTickDataAdapter.classifyTrade(100.0, 0, 0));
    }

    @Test
    void snapshotWindow_includesOnlyTicksWithinWindow() {
        // 5-min default window holds all ticks; snapshotWindow(10s) should drop the older ones
        var agg = new TickByTickAggregator(Instrument.MNQ);
        var now = Instant.now();
        // Old tick: 30s ago, BUY 100
        agg.onTick(27000.0, 100, BUY, now.minusSeconds(30));
        // Recent ticks within 10s window
        agg.onTick(27001.0, 50, BUY, now.minusSeconds(5));
        agg.onTick(27002.0, 30, SELL, now.minusSeconds(2));

        var full = agg.snapshot();
        assertEquals(150, full.buyVolume());
        assertEquals(30, full.sellVolume());

        var recent = agg.snapshotWindow(10);
        assertEquals(50, recent.buyVolume(), "old 30s tick should be excluded");
        assertEquals(30, recent.sellVolume());
        assertEquals(20, recent.delta());
    }

    @Test
    void snapshotWindow_emptyWhenNoRecentTicks() {
        var agg = new TickByTickAggregator(Instrument.MNQ);
        var now = Instant.now();
        // Only old ticks
        agg.onTick(27000.0, 100, BUY, now.minusSeconds(120));

        var recent = agg.snapshotWindow(10);
        assertEquals(0, recent.buyVolume());
        assertEquals(0, recent.sellVolume());
        assertEquals(0, recent.delta());
    }

    @Test
    void snapshotWindow_doesNotMutateTrendState() {
        // snapshot() updates previousCumulativeDelta; snapshotWindow() must not — they
        // observe different time horizons and would corrupt each other's trend signal.
        var agg = new TickByTickAggregator(Instrument.MNQ);
        var now = Instant.now();
        agg.onTick(27000.0, 100, BUY, now.minusSeconds(2));
        agg.onTick(27001.0, 50, SELL, now.minusSeconds(1));

        // Establish trend state via snapshot()
        var s1 = agg.snapshot();
        // Now call snapshotWindow several times — should not affect the next snapshot()'s trend
        agg.snapshotWindow(5);
        agg.snapshotWindow(5);

        // The next full snapshot should compute trend relative to s1, not the window snapshots
        var s2 = agg.snapshot();
        assertEquals(s1.delta(), s2.delta(), "delta unchanged when no new ticks");
        // Trend on identical delta should be FLAT (no change vs previous)
        assertEquals("FLAT", s2.deltaTrend());
    }
}
