package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.TickBar;
import com.riskdesk.domain.orderflow.port.TickBarStorePort;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the adapter maintains one aggregator per (instrument, size): the base size
 * plus each configured coarse size, all fed from the same classified-trade stream.
 */
class IbkrTickBarAdapterTest {

    private final Instant t0 = Instant.parse("2026-06-10T14:30:00Z");

    private OrderFlowProperties propsWith(int base, List<Integer> coarseSizes) {
        OrderFlowProperties props = new OrderFlowProperties();
        OrderFlowProperties.TickChart tc = props.getTickChart();
        tc.setTicksPerBar(new HashMap<>()); // no per-instrument override → fall back to the default base
        tc.setDefaultTicksPerBar(base);
        tc.setCoarseSizes(coarseSizes);
        tc.setCoarseMaxBars(50);
        tc.setMaxBars(100);
        tc.getPersistence().setEnabled(false); // keep onTick free of persistence side effects
        return props;
    }

    /** Feeds n BUY ticks at increasing prices (size 1 each) for MNQ. */
    private void feed(IbkrTickBarAdapter adapter, int n) {
        for (int i = 0; i < n; i++) {
            adapter.onTick(Instrument.MNQ, 100.0 + i * 0.25, 1, "BUY", t0.plusSeconds(i));
        }
    }

    /** A completed MNQ bar at the given size/seq, as the persistence store would return it. */
    private TickBar completed(int ticksPerBar, long seq) {
        long ts = t0.getEpochSecond();
        return new TickBar("MNQ", ticksPerBar, seq, ts, ts,
            100.0, 101.0, 99.0, 100.5, 10, 6, 4, 2, ticksPerBar, true);
    }

    @Test
    void feedsBaseAndCoarseAggregatorsFromTheSameTickStream() {
        IbkrTickBarAdapter adapter =
            new IbkrTickBarAdapter(propsWith(10, List.of(20)), mock(TickBarStorePort.class));

        feed(adapter, 20);

        // Base size 10 → 2 completed bars.
        List<TickBar> base = adapter.recentBars(Instrument.MNQ, 10, 100);
        assertEquals(2, base.size());
        assertTrue(base.stream().allMatch(b -> b.ticksPerBar() == 10 && b.complete()));

        // The no-size overload resolves to the base series.
        assertEquals(base.size(), adapter.recentBars(Instrument.MNQ, 100).size());

        // Coarse size 20 → a single completed bar spanning all 20 ticks.
        List<TickBar> coarse = adapter.recentBars(Instrument.MNQ, 20, 100);
        assertEquals(1, coarse.size());
        TickBar bar = coarse.get(0);
        assertEquals(20, bar.ticksPerBar());
        assertEquals(20, bar.tickCount());
        assertTrue(bar.complete());
        assertEquals(100.0, bar.open());
        assertEquals(104.75, bar.high());
        assertEquals(100.0, bar.low());
        assertEquals(104.75, bar.close());
        assertEquals(20L, bar.volume());
        assertEquals(20L, bar.delta());
    }

    @Test
    void coarseSizeNotAMultipleOfBaseIsIgnored() {
        IbkrTickBarAdapter adapter =
            new IbkrTickBarAdapter(propsWith(10, List.of(15)), mock(TickBarStorePort.class));

        feed(adapter, 30);

        assertEquals(3, adapter.recentBars(Instrument.MNQ, 10, 100).size()); // base still aggregates
        assertTrue(adapter.recentBars(Instrument.MNQ, 15, 100).isEmpty());   // 15 % 10 != 0 → no aggregator
    }

    @Test
    void exposesInProgressCoarseBarBeforeCompletion() {
        IbkrTickBarAdapter adapter =
            new IbkrTickBarAdapter(propsWith(10, List.of(20)), mock(TickBarStorePort.class));

        feed(adapter, 25); // coarse 20 → 1 completed (seq 0) + 1 in-progress head (seq 1, 5 ticks)

        List<TickBar> coarse = adapter.recentBars(Instrument.MNQ, 20, 100);
        assertEquals(2, coarse.size());
        assertTrue(coarse.get(0).complete());
        assertEquals(20, coarse.get(0).tickCount());

        TickBar head = coarse.get(1); // the live bar the 2s poll refreshes
        assertFalse(head.complete());
        assertEquals(5, head.tickCount());
        assertEquals(1, head.seq());
    }

    @Test
    void restoresEachSizeFromStoreAndContinuesSeq() {
        OrderFlowProperties props = propsWith(10, List.of(20));
        props.getTickChart().getPersistence().setEnabled(true);
        TickBarStorePort store = mock(TickBarStorePort.class);
        when(store.loadRecent(eq(Instrument.MNQ), eq(10), anyInt()))
            .thenReturn(List.of(completed(10, 0), completed(10, 1)));
        when(store.loadRecent(eq(Instrument.MNQ), eq(20), anyInt()))
            .thenReturn(List.of(completed(20, 0)));
        IbkrTickBarAdapter adapter = new IbkrTickBarAdapter(props, store);

        adapter.restoreFromStore();

        // Both sizes restored from their own ticks_per_bar stream.
        List<TickBar> base = adapter.recentBars(Instrument.MNQ, 10, 100);
        assertEquals(2, base.size());
        assertEquals(1, base.get(base.size() - 1).seq());
        List<TickBar> coarse = adapter.recentBars(Instrument.MNQ, 20, 100);
        assertEquals(1, coarse.size());
        assertEquals(20, coarse.get(0).ticksPerBar());

        // A live tick resumes after the restored seq (monotonic across restart), not at 0.
        adapter.onTick(Instrument.MNQ, 100.0, 1, "BUY", t0);
        List<TickBar> after = adapter.recentBars(Instrument.MNQ, 10, 100);
        TickBar head = after.get(after.size() - 1);
        assertFalse(head.complete());
        assertEquals(2, head.seq());
    }

    @Test
    void purgeInstrumentClearsAllSizes() {
        IbkrTickBarAdapter adapter =
            new IbkrTickBarAdapter(propsWith(10, List.of(20)), mock(TickBarStorePort.class));
        feed(adapter, 20);
        assertFalse(adapter.recentBars(Instrument.MNQ, 10, 100).isEmpty());
        assertFalse(adapter.recentBars(Instrument.MNQ, 20, 100).isEmpty());

        adapter.purgeInstrument(Instrument.MNQ);

        assertTrue(adapter.recentBars(Instrument.MNQ, 10, 100).isEmpty());
        assertTrue(adapter.recentBars(Instrument.MNQ, 20, 100).isEmpty());
    }
}
