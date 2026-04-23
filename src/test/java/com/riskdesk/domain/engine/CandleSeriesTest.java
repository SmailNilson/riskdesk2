package com.riskdesk.domain.engine;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandleSeriesTest {

    private static final Instant T0 = Instant.parse("2026-04-16T12:00:00Z");

    /** Zero-arg candle used by tests that do not care about chronology — uses fixed T0. */
    private Candle candle(double close) {
        return candle(close, T0);
    }

    private Candle candle(double close, Instant timestamp) {
        return new Candle(
                Instrument.MNQ, "5m", timestamp,
                BigDecimal.valueOf(close),
                BigDecimal.valueOf(close + 1),
                BigDecimal.valueOf(close - 1),
                BigDecimal.valueOf(close),
                100L
        );
    }

    @Test
    void size_returnsCorrectCount() {
        CandleSeries series = new CandleSeries(List.of(candle(100), candle(101), candle(102)));
        assertEquals(3, series.size());
    }

    @Test
    void isEmpty_trueForEmptySeries() {
        CandleSeries series = new CandleSeries(List.of());
        assertTrue(series.isEmpty());
    }

    @Test
    void isEmpty_falseForNonEmptySeries() {
        CandleSeries series = new CandleSeries(List.of(candle(100)));
        assertFalse(series.isEmpty());
    }

    @Test
    void get_returnsCandleAtIndex() {
        Candle c0 = candle(100);
        Candle c1 = candle(200);
        CandleSeries series = new CandleSeries(List.of(c0, c1));

        assertSame(c0, series.get(0));
        assertSame(c1, series.get(1));
    }

    @Test
    void get_throwsOnOutOfBounds() {
        CandleSeries series = new CandleSeries(List.of(candle(100)));
        assertThrows(IndexOutOfBoundsException.class, () -> series.get(5));
    }

    @Test
    void latest_returnsLastCandle() {
        Candle last = candle(300);
        CandleSeries series = new CandleSeries(List.of(candle(100), candle(200), last));
        assertSame(last, series.latest());
    }

    @Test
    void latest_returnsNullForEmptySeries() {
        CandleSeries series = new CandleSeries(List.of());
        assertNull(series.latest());
    }

    @Test
    void tail_returnsLastNCandles() {
        Candle c0 = candle(100);
        Candle c1 = candle(200);
        Candle c2 = candle(300);
        CandleSeries series = new CandleSeries(List.of(c0, c1, c2));

        CandleSeries tail = series.tail(2);
        assertEquals(2, tail.size());
        assertSame(c1, tail.get(0));
        assertSame(c2, tail.get(1));
    }

    @Test
    void tail_returnsSameInstanceWhenNExceedsSize() {
        CandleSeries series = new CandleSeries(List.of(candle(100), candle(200)));
        CandleSeries tail = series.tail(10);
        assertSame(series, tail);
    }

    @Test
    void tail_returnsSameInstanceWhenNEqualsSize() {
        CandleSeries series = new CandleSeries(List.of(candle(100), candle(200)));
        CandleSeries tail = series.tail(2);
        assertSame(series, tail);
    }

    @Test
    void immutability_modifyingOriginalListDoesNotAffectSeries() {
        List<Candle> mutableList = new ArrayList<>();
        mutableList.add(candle(100));
        mutableList.add(candle(200));

        CandleSeries series = new CandleSeries(mutableList);

        // Modify the original list after construction
        mutableList.add(candle(300));
        mutableList.clear();

        // Series should still have the original two candles
        assertEquals(2, series.size());
        assertFalse(series.isEmpty());
    }

    @Test
    void asList_returnsUnmodifiableList() {
        CandleSeries series = new CandleSeries(List.of(candle(100)));
        List<Candle> list = series.asList();
        assertThrows(UnsupportedOperationException.class, () -> list.add(candle(200)));
    }

    @Test
    void asList_containsAllCandles() {
        Candle c0 = candle(100);
        Candle c1 = candle(200);
        CandleSeries series = new CandleSeries(List.of(c0, c1));

        List<Candle> list = series.asList();
        assertEquals(2, list.size());
        assertSame(c0, list.get(0));
        assertSame(c1, list.get(1));
    }

    // ── Order validation (PR-16) ─────────────────────────────────────────

    @Test
    void construct_nullList_throwsNpeWithArgumentName() {
        NullPointerException npe = assertThrows(NullPointerException.class,
            () -> new CandleSeries(null));
        assertTrue(npe.getMessage().contains("candles"),
            "NPE message should name the offending argument, got: " + npe.getMessage());
    }

    @Test
    void construct_nullCandleInList_throwsIllegalArgumentWithIndex() {
        Candle c0 = candle(100, T0);
        Candle c1 = candle(200, T0.plusSeconds(300));
        List<Candle> withNull = Arrays.asList(c0, null, c1);

        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
            () -> new CandleSeries(withNull));
        assertTrue(iae.getMessage().contains("candles[1]"),
            "IAE should pin-point the null slot, got: " + iae.getMessage());
    }

    @Test
    void construct_nullTimestampOnCandle_throwsIllegalArgumentWithIndex() {
        Candle good = candle(100, T0);
        Candle bad = new Candle(
            Instrument.MNQ, "5m", null,
            BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 1L);

        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
            () -> new CandleSeries(List.of(good, bad)));
        assertTrue(iae.getMessage().contains("candles[1].timestamp"),
            "IAE should pin-point the bad candle, got: " + iae.getMessage());
    }

    @Test
    void construct_decreasingTimestamps_throwsIllegalArgument() {
        Candle c0 = candle(100, T0);
        Candle c1 = candle(200, T0.plusSeconds(300));
        Candle c2 = candle(300, T0.plusSeconds(60));  // travels backwards in time

        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
            () -> new CandleSeries(List.of(c0, c1, c2)));
        assertTrue(iae.getMessage().contains("not chronologically ordered"),
            "IAE should describe the ordering violation, got: " + iae.getMessage());
        assertTrue(iae.getMessage().contains("candles[2]"),
            "IAE should pin-point the offending pair, got: " + iae.getMessage());
    }

    @Test
    void construct_equalTimestamps_allowed() {
        // Real-time feeds occasionally emit two candles sharing a millisecond
        // boundary. They're unusual but not corrupting — we must NOT hard-reject.
        Candle c0 = candle(100, T0);
        Candle c1 = candle(200, T0);
        Candle c2 = candle(300, T0);

        assertDoesNotThrow(() -> new CandleSeries(List.of(c0, c1, c2)));
    }

    @Test
    void construct_strictlyIncreasingTimestamps_succeeds() {
        Candle c0 = candle(100, T0);
        Candle c1 = candle(200, T0.plusSeconds(300));
        Candle c2 = candle(300, T0.plusSeconds(600));

        CandleSeries series = new CandleSeries(List.of(c0, c1, c2));

        assertEquals(3, series.size());
        assertSame(c2, series.latest());
    }

    @Test
    void construct_emptyList_succeeds() {
        CandleSeries series = new CandleSeries(List.of());
        assertTrue(series.isEmpty());
    }

    @Test
    void tail_onOrderedSeries_remainsValid() {
        Candle c0 = candle(100, T0);
        Candle c1 = candle(200, T0.plusSeconds(300));
        Candle c2 = candle(300, T0.plusSeconds(600));
        CandleSeries series = new CandleSeries(List.of(c0, c1, c2));

        CandleSeries tail = series.tail(2);

        assertEquals(2, tail.size());
        assertSame(c1, tail.get(0));
        assertSame(c2, tail.get(1));
    }
}
