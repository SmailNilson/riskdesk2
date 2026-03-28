package com.riskdesk.domain.engine.smc;

import com.riskdesk.domain.engine.smc.SmcStructureEngine.*;
import com.riskdesk.domain.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that {@link SmcStructureEngine} detects Internal and Swing
 * structure breaks exactly like the LuxAlgo "Smart Money Concepts" script.
 * <p>
 * All OHLC data is hardcoded to allow deterministic, math-level verification.
 * <p>
 * <b>Lookback defaults used in tests:</b>
 * <ul>
 *   <li>Internal: 2 (small for concise test data; production default is 5)</li>
 *   <li>Swing: 5 or 10 (production default is 50)</li>
 * </ul>
 */
class SmcStructureEngineTest {

    // ── Helper ───────────────────────────────────────────────────────────

    private static Candle bar(int index, double high, double low, double close) {
        Candle c = new Candle();
        c.setTimestamp(Instant.EPOCH.plusSeconds(index * 60L));
        c.setOpen(BigDecimal.valueOf((high + low) / 2));
        c.setHigh(BigDecimal.valueOf(high));
        c.setLow(BigDecimal.valueOf(low));
        c.setClose(BigDecimal.valueOf(close));
        return c;
    }

    private List<StructureEvent> feedAll(SmcStructureEngine engine,
                                         double[][] data, int startIndex) {
        List<StructureEvent> all = new ArrayList<>();
        for (int i = 0; i < data.length; i++) {
            all.addAll(engine.onCandle(
                    bar(startIndex + i, data[i][0], data[i][1], data[i][2])));
        }
        return all;
    }

    // ── OHLC datasets ────────────────────────────────────────────────────

    /**
     * V-shaped: uptrend (0-2), peak (3-4), drop (5-8), recovery (9-12).
     * With internal lookback=2:
     *   bar 2 → LOW pivot at bar 0 (price=8)
     *   bar 4 → HIGH pivot at bar 2 (price=14)
     *   bar 5 → bearish BOS (close 7.5 crosses below 8)
     *   bar 10 → LOW pivot at bar 8 (price=3)
     *   bar 12 → bullish CHoCH (close 15 crosses above 14)
     */
    private static final double[][] BASE_OHLC = {
            //  H      L      C
            {  10,     8,     9   },  // 0
            {  12,     9,    11   },  // 1
            {  14,    11,    13   },  // 2
            {  13,    10,    11   },  // 3
            {  11,     8.5,   9   },  // 4
            {   9,     7,     7.5 },  // 5  ← internal BOS bearish
            {   7.5,   5,     6   },  // 6
            {   6,     4,     5   },  // 7
            {   5,     3,     4   },  // 8
            {   7,     3.5,   6   },  // 9
            {  10,     6,     9   },  // 10
            {  13,     9,    12   },  // 11
            {  16,    12,    15   },  // 12 ← internal CHoCH bullish
    };

    /**
     * Continuation of BASE_OHLC (bars 13-28).
     * With swing lookback=5:
     *   bar 13 → swing LOW pivot at bar 8 (price=3)
     *   bar 17 → swing HIGH pivot at bar 12 (price=16)
     *   bar 22 → swing BOS bearish (close 2.5 crosses below 3)
     *   bar 28 → swing CHoCH bullish (close 16.5 crosses above 16)
     */
    private static final double[][] EXTENDED_OHLC = {
            //  H      L      C
            {  15,    14,    15   },  // 13  swing LOW detected (bar 8)
            {  15.5,  14,    14.5 },  // 14
            {  15,    13.5,  14   },  // 15
            {  14.5,  13,    13.5 },  // 16
            {  14,    12.5,  13   },  // 17  swing HIGH detected (bar 12)
            {  13.5,  12,    12.5 },  // 18
            {  13,    10,    11   },  // 19
            {  11.5,   8,     9   },  // 20
            {   9.5,   5,     6   },  // 21
            {   6.5,   2,     2.5 },  // 22  ← swing BOS bearish
            {   3,     1,     1.5 },  // 23
            {   2,     0.5,   1   },  // 24
            {   4,     0.5,   3.5 },  // 25
            {   8,     3,     7   },  // 26
            {  12,     6,    11   },  // 27
            {  17,    10,    16.5 },  // 28  ← swing CHoCH bullish
    };

    // ── Test 1: Internal fires, Swing stays silent ───────────────────────

    @Test
    void internalDetectsEvents_swingStaysSilent() {
        // swing lookback=10 → needs 11 bars minimum and won't find pivots in 13 bars
        SmcStructureEngine engine = new SmcStructureEngine(2, 10);
        List<StructureEvent> events = feedAll(engine, BASE_OHLC, 0);

        List<StructureEvent> internal = events.stream()
                .filter(e -> e.level() == StructureLevel.INTERNAL).toList();
        List<StructureEvent> swing = events.stream()
                .filter(e -> e.level() == StructureLevel.SWING).toList();

        assertEquals(2, internal.size(), "Expected 2 internal events");
        assertTrue(swing.isEmpty(), "Swing should stay silent with lookback=10 on 13 bars");

        // Event 1: BOS BEARISH at bar 5 — close(7.5) crosses below LOW pivot(8)
        StructureEvent bos = internal.get(0);
        assertAll("Internal BOS bearish",
                () -> assertEquals(StructureType.BOS, bos.type()),
                () -> assertEquals(Bias.BEARISH, bos.newBias()),
                () -> assertEquals(8.0, bos.breakPrice()),
                () -> assertEquals(5, bos.barIndex())
        );

        // Event 2: CHoCH BULLISH at bar 12 — close(15) crosses above HIGH pivot(14)
        StructureEvent choch = internal.get(1);
        assertAll("Internal CHoCH bullish",
                () -> assertEquals(StructureType.CHOCH, choch.type()),
                () -> assertEquals(Bias.BULLISH, choch.newBias()),
                () -> assertEquals(14.0, choch.breakPrice()),
                () -> assertEquals(12, choch.barIndex())
        );
    }

    // ── Test 2: Swing fires with extended data ───────────────────────────

    @Test
    void swingDetectsStructureBreaks() {
        SmcStructureEngine engine = new SmcStructureEngine(2, 5);

        // Feed base data (bars 0-12): internal events fire here
        feedAll(engine, BASE_OHLC, 0);

        // Feed extended data (bars 13-28): swing events expected here
        List<StructureEvent> events = feedAll(engine, EXTENDED_OHLC, BASE_OHLC.length);
        List<StructureEvent> swingEvents = events.stream()
                .filter(e -> e.level() == StructureLevel.SWING).toList();

        assertEquals(2, swingEvents.size(), "Expected 2 swing events in extended data");

        // Swing BOS BEARISH at bar 22 — close(2.5) crosses below swing LOW(3)
        StructureEvent bos = swingEvents.get(0);
        assertAll("Swing BOS bearish",
                () -> assertEquals(StructureType.BOS, bos.type()),
                () -> assertEquals(Bias.BEARISH, bos.newBias()),
                () -> assertEquals(3.0, bos.breakPrice()),
                () -> assertEquals(22, bos.barIndex())
        );

        // Swing CHoCH BULLISH at bar 28 — close(16.5) crosses above swing HIGH(16)
        StructureEvent choch = swingEvents.get(1);
        assertAll("Swing CHoCH bullish",
                () -> assertEquals(StructureType.CHOCH, choch.type()),
                () -> assertEquals(Bias.BULLISH, choch.newBias()),
                () -> assertEquals(16.0, choch.breakPrice()),
                () -> assertEquals(28, choch.barIndex())
        );
    }

    // ── Test 3: Internal breaks suppressed when pivot matches swing ──────

    @Test
    void internalBreakSuppressed_whenPivotMatchesSwing() {
        SmcStructureEngine engine = new SmcStructureEngine(2, 5);

        // Feed all 29 bars
        List<StructureEvent> allEvents = new ArrayList<>();
        allEvents.addAll(feedAll(engine, BASE_OHLC, 0));
        allEvents.addAll(feedAll(engine, EXTENDED_OHLC, BASE_OHLC.length));

        // Internal events: only bars 5 and 12 (from BASE_OHLC)
        // Bars 22 and 28 are suppressed because internal pivots match swing pivots
        List<StructureEvent> internal = allEvents.stream()
                .filter(e -> e.level() == StructureLevel.INTERNAL).toList();

        assertEquals(2, internal.size(),
                "Internal should have exactly 2 events; bars 22/28 suppressed by dedup");
        assertEquals(5, internal.get(0).barIndex());
        assertEquals(12, internal.get(1).barIndex());
    }

    // ── Test 4: Snapshot reflects correct state ──────────────────────────

    @Test
    void snapshotReflectsCurrentState() {
        SmcStructureEngine engine = new SmcStructureEngine(2, 10);
        feedAll(engine, BASE_OHLC, 0);

        StructureSnapshot snap = engine.snapshot();

        // Internal bias is BULLISH after CHoCH at bar 12
        assertEquals(Bias.BULLISH, snap.internalBias());

        // Swing bias stays null (no swing events with lookback=10 on 13 bars)
        assertNull(snap.swingBias());

        // Internal pivots: HIGH at bar 2 (price=14), LOW at bar 8 (price=3)
        assertNotNull(snap.internalHigh());
        assertEquals(14.0, snap.internalHigh().price());
        assertEquals(2, snap.internalHigh().barIndex());

        assertNotNull(snap.internalLow());
        assertEquals(3.0, snap.internalLow().price());
        assertEquals(8, snap.internalLow().barIndex());

        // Swing pivots should be null
        assertNull(snap.swingHigh());
        assertNull(snap.swingLow());

        // Total bars processed
        assertEquals(13, engine.totalBars());
    }

    // ── Test 5: Engine emits nothing with insufficient data ──────────────

    @Test
    void noEventsBeforeEnoughBarsForPivot() {
        SmcStructureEngine engine = new SmcStructureEngine(5, 50);

        // Feed only 4 bars — not enough for internal (needs 6) or swing (needs 51)
        double[][] tinyData = {
                {10, 8, 9}, {12, 9, 11}, {14, 11, 13}, {13, 10, 11}
        };
        List<StructureEvent> events = feedAll(engine, tinyData, 0);

        assertTrue(events.isEmpty());
        assertNull(engine.internalBias());
        assertNull(engine.swingBias());
        assertEquals(4, engine.totalBars());
    }
}
