package com.riskdesk.domain.engine;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandleSeriesTest {

    private Candle candle(double close) {
        return new Candle(
                Instrument.MNQ, "5m", Instant.now(),
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
}
