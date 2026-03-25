package com.riskdesk.infrastructure.marketdata;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandleAccumulatorTest {

    @Test
    void firstTick_initializesOhlcCorrectly() {
        CandleAccumulator accumulator = new CandleAccumulator();
        List<Candle> closed = new ArrayList<>();
        BigDecimal price = new BigDecimal("62.40");

        // First tick should not close any candle
        accumulator.accumulate(Instrument.MCL, "10m", 600, price, 1, closed::add);

        assertTrue(closed.isEmpty(), "First tick should not close a candle");
    }

    @Test
    void secondTick_withinSamePeriod_updatesHighLowClose() {
        CandleAccumulator accumulator = new CandleAccumulator();
        List<Candle> closed = new ArrayList<>();

        BigDecimal price1 = new BigDecimal("62.40");
        BigDecimal price2 = new BigDecimal("62.60");

        // Same period, two ticks
        accumulator.accumulate(Instrument.MCL, "10m", 600, price1, 1, closed::add);
        accumulator.accumulate(Instrument.MCL, "10m", 600, price2, 1, closed::add);

        // No candle closed yet (still in same period)
        assertTrue(closed.isEmpty(), "Same-period ticks should not close a candle");
    }

    @Test
    void periodRollover_triggersCallbackWithCompletedCandle() {
        // Use a very short period (1 second) so we can trigger rollover with a sleep
        CandleAccumulator accumulator = new CandleAccumulator();
        List<Candle> closed = new ArrayList<>();

        BigDecimal price1 = new BigDecimal("62.40");
        BigDecimal price2 = new BigDecimal("62.80");

        // First tick in current period
        accumulator.accumulate(Instrument.MCL, "1s", 1, price1, 1, closed::add);
        accumulator.accumulate(Instrument.MCL, "1s", 1, price2, 1, closed::add);

        // Wait for period boundary
        try { Thread.sleep(1100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        BigDecimal price3 = new BigDecimal("63.00");
        accumulator.accumulate(Instrument.MCL, "1s", 1, price3, 1, closed::add);

        assertEquals(1, closed.size(), "Period rollover should close one candle");
        Candle candle = closed.get(0);
        assertEquals(Instrument.MCL, candle.getInstrument());
        assertEquals("1s", candle.getTimeframe());
        assertEquals(price1, candle.getOpen());
        assertEquals(price2, candle.getHigh());
        assertEquals(price1, candle.getLow());
        assertEquals(price2, candle.getClose());
        assertEquals(2, candle.getVolume());
    }

    @Test
    void multipleInstruments_trackedIndependently() {
        CandleAccumulator accumulator = new CandleAccumulator();
        List<Candle> closed = new ArrayList<>();

        BigDecimal mclPrice = new BigDecimal("62.40");
        BigDecimal mnqPrice = new BigDecimal("18250.00");

        // Accumulate for two instruments in the same period
        accumulator.accumulate(Instrument.MCL, "10m", 600, mclPrice, 1, closed::add);
        accumulator.accumulate(Instrument.MNQ, "10m", 600, mnqPrice, 1, closed::add);

        // No candles closed
        assertTrue(closed.isEmpty());

        // Update with different prices
        accumulator.accumulate(Instrument.MCL, "10m", 600, new BigDecimal("62.50"), 1, closed::add);
        accumulator.accumulate(Instrument.MNQ, "10m", 600, new BigDecimal("18260.00"), 1, closed::add);

        // Still no candles closed (same period)
        assertTrue(closed.isEmpty());
    }
}
