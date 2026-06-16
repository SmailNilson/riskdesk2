package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.TickBar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TickBarAggregatorTest {

    private final Instant t0 = Instant.parse("2026-06-10T14:30:00Z");

    @Test
    void barCompletesAfterExactlyNTicks() {
        TickBarAggregator agg = new TickBarAggregator(Instrument.MNQ, 3, 10);

        assertTrue(agg.onTick(29000.0, 2, "BUY", t0).isEmpty());
        assertTrue(agg.onTick(29002.5, 1, "SELL", t0.plusSeconds(1)).isEmpty());
        Optional<TickBar> closed = agg.onTick(28998.0, 4, "BUY", t0.plusSeconds(3));

        assertTrue(closed.isPresent());
        TickBar bar = closed.get();
        assertTrue(bar.complete());
        assertEquals(3, bar.tickCount());
        assertEquals(29000.0, bar.open());
        assertEquals(29002.5, bar.high());
        assertEquals(28998.0, bar.low());
        assertEquals(28998.0, bar.close());
        assertEquals(7, bar.volume());
        assertEquals(6, bar.buyVolume());
        assertEquals(1, bar.sellVolume());
        assertEquals(5, bar.delta());
        assertEquals(t0.getEpochSecond(), bar.openTime());
        assertEquals(t0.plusSeconds(3).getEpochSecond(), bar.closeTime());
        assertEquals(0, bar.seq());
    }

    @Test
    void recentBars_returnsCompletedThenInProgress_withIncreasingSeq() {
        TickBarAggregator agg = new TickBarAggregator(Instrument.MNQ, 2, 10);
        agg.onTick(100.0, 1, "BUY", t0);
        agg.onTick(101.0, 1, "BUY", t0.plusSeconds(1));   // closes seq 0
        agg.onTick(102.0, 1, "SELL", t0.plusSeconds(2));
        agg.onTick(103.0, 1, "SELL", t0.plusSeconds(3));  // closes seq 1
        agg.onTick(104.0, 1, "BUY", t0.plusSeconds(4));   // in-progress seq 2

        List<TickBar> bars = agg.recentBars(10);
        assertEquals(3, bars.size());
        assertEquals(0, bars.get(0).seq());
        assertTrue(bars.get(0).complete());
        assertEquals(1, bars.get(1).seq());
        assertTrue(bars.get(1).complete());
        assertEquals(2, bars.get(2).seq());
        assertFalse(bars.get(2).complete());
        assertEquals(1, bars.get(2).tickCount());
    }

    @Test
    void recentBars_respectsLimit_keepingNewest() {
        TickBarAggregator agg = new TickBarAggregator(Instrument.MNQ, 1, 10);
        for (int i = 0; i < 5; i++) {
            agg.onTick(100.0 + i, 1, "BUY", t0.plusSeconds(i)); // each tick closes a bar
        }
        List<TickBar> bars = agg.recentBars(2);
        assertEquals(2, bars.size());
        assertEquals(3, bars.get(0).seq());
        assertEquals(4, bars.get(1).seq());
    }

    @Test
    void ringBufferEvictsOldestCompletedBars() {
        TickBarAggregator agg = new TickBarAggregator(Instrument.MNQ, 1, 3);
        for (int i = 0; i < 6; i++) {
            agg.onTick(100.0 + i, 1, "BUY", t0.plusSeconds(i));
        }
        List<TickBar> bars = agg.recentBars(100);
        assertEquals(3, bars.size());
        assertEquals(3, bars.get(0).seq()); // 0,1,2 evicted
        assertEquals(5, bars.get(2).seq());
    }

    @Test
    void unclassifiedAndZeroSizeTicksAreIgnored() {
        TickBarAggregator agg = new TickBarAggregator(Instrument.MNQ, 2, 10);
        agg.onTick(100.0, 0, "BUY", t0);
        agg.onTick(100.0, 1, "UNCLASSIFIED", t0);
        assertTrue(agg.currentBar().isEmpty());
        assertTrue(agg.recentBars(10).isEmpty());
    }

    @Test
    void constructorRejectsNonPositiveSizes() {
        assertThrows(IllegalArgumentException.class, () -> new TickBarAggregator(Instrument.MNQ, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TickBarAggregator(Instrument.MNQ, 10, 0));
    }

    @Test
    void restore_seedsCompletedBarsOldestFirst_andResumesSeqMonotonically() {
        TickBarAggregator agg = new TickBarAggregator(Instrument.MNQ, 2, 10);
        // Supplied out of order to verify restore sorts by seq.
        agg.restore(List.of(bar(2, 2), bar(0, 2), bar(1, 2)));

        List<TickBar> restored = agg.recentBars(10);
        assertEquals(3, restored.size());
        assertEquals(0, restored.get(0).seq());
        assertEquals(1, restored.get(1).seq());
        assertEquals(2, restored.get(2).seq());
        assertTrue(restored.stream().allMatch(TickBar::complete));

        // New bar continues the sequence at maxSeq + 1 (not back at 0).
        agg.onTick(100.0, 1, "BUY", t0);
        Optional<TickBar> closed = agg.onTick(101.0, 1, "BUY", t0.plusSeconds(1));
        assertTrue(closed.isPresent());
        assertEquals(3, closed.get().seq());
    }

    @Test
    void restore_capsAtMaxBars_keepingNewest_andResumesSeq() {
        TickBarAggregator agg = new TickBarAggregator(Instrument.MNQ, 2, 3);
        agg.restore(List.of(bar(0, 2), bar(1, 2), bar(2, 2), bar(3, 2), bar(4, 2)));

        List<TickBar> restored = agg.recentBars(100);
        assertEquals(3, restored.size());
        assertEquals(2, restored.get(0).seq()); // 0,1 evicted
        assertEquals(4, restored.get(2).seq());

        agg.onTick(100.0, 1, "BUY", t0);
        Optional<TickBar> closed = agg.onTick(101.0, 1, "BUY", t0.plusSeconds(1));
        assertEquals(5, closed.orElseThrow().seq()); // continues from the highest restored seq
    }

    @Test
    void restore_skipsBarsWithMismatchedTicksPerBar() {
        TickBarAggregator agg = new TickBarAggregator(Instrument.MNQ, 200, 10);
        // bar at seq 2 was built with a different bar size (stale config / pre-rollover) → skipped.
        agg.restore(List.of(bar(0, 200), bar(1, 200), bar(2, 100)));

        List<TickBar> restored = agg.recentBars(10);
        assertEquals(2, restored.size());
        assertEquals(0, restored.get(0).seq());
        assertEquals(1, restored.get(1).seq());
    }

    @Test
    void restore_emptyOrNull_isNoOp() {
        TickBarAggregator agg = new TickBarAggregator(Instrument.MNQ, 2, 10);
        agg.restore(List.of());
        agg.restore(null);
        assertTrue(agg.isEmpty());
        assertTrue(agg.recentBars(10).isEmpty());
    }

    @Test
    void restore_afterTicksProcessed_throws() {
        TickBarAggregator agg = new TickBarAggregator(Instrument.MNQ, 2, 10);
        agg.onTick(100.0, 1, "BUY", t0);
        assertFalse(agg.isEmpty());
        assertThrows(IllegalStateException.class, () -> agg.restore(List.of(bar(0, 2))));
    }

    /** A completed bar at the given seq/bar-size with arbitrary but consistent OHLC. */
    private TickBar bar(long seq, int ticksPerBar) {
        long openTime = t0.getEpochSecond() + seq * 10;
        long closeTime = openTime + 5;
        return new TickBar(Instrument.MNQ.name(), ticksPerBar, seq,
            openTime, closeTime, 100.0, 101.0, 99.0, 100.5,
            ticksPerBar, ticksPerBar - 1, 1, ticksPerBar - 2, ticksPerBar, true);
    }
}
