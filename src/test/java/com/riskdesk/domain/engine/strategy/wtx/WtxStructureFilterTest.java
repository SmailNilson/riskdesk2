package com.riskdesk.domain.engine.strategy.wtx;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WtxStructureFilterTest {

    private static final BigDecimal ATR = BigDecimal.valueOf(1.0);
    private static final int LOOKBACK = 12;
    private static final BigDecimal SWEEP_BUFFER = BigDecimal.valueOf(0.05);

    @Test
    void insufficientCandles_failSafeAllows() {
        List<Candle> few = List.of(candle(100, 101, 99, 100));
        WtxStructureFilter.Decision d = WtxStructureFilter.evaluate("LONG", few, ATR, LOOKBACK, SWEEP_BUFFER);
        assertTrue(d.allows());
        assertEquals(WtxStructureFilter.StructureReason.UNAVAILABLE, d.reason());
    }

    @Test
    void lowSweepReclaim_allowsLong() {
        List<Candle> candles = buildFlatHistory(100, LOOKBACK + 2);
        // Replace last bar with a sweep-and-reclaim: low pierced 99 by more than 0.05*ATR, close > 99
        candles.set(candles.size() - 1, candle(100, 100.5, 98.5, 100.1));
        WtxStructureFilter.Decision d = WtxStructureFilter.evaluate("LONG", candles, ATR, LOOKBACK, SWEEP_BUFFER);
        assertTrue(d.allows());
        assertEquals(WtxStructureFilter.StructureReason.LOW_SWEEP_RECLAIM, d.reason());
    }

    @Test
    void highSweepReject_allowsShort() {
        List<Candle> candles = buildFlatHistory(100, LOOKBACK + 2);
        // Last bar: high pierces 101 (the rolling max) by more than buffer, close back below
        candles.set(candles.size() - 1, candle(100, 102.0, 99.5, 100));
        WtxStructureFilter.Decision d = WtxStructureFilter.evaluate("SHORT", candles, ATR, LOOKBACK, SWEEP_BUFFER);
        assertTrue(d.allows());
        assertEquals(WtxStructureFilter.StructureReason.HIGH_SWEEP_REJECT, d.reason());
    }

    @Test
    void bullishReclaim_allowsLong() {
        List<Candle> candles = buildFlatHistory(100, LOOKBACK + 2);
        // Last bar: close > open, close > midBodyPrev, low <= priorLow + 0.25*ATR
        candles.set(candles.size() - 1, candle(99, 101, 99.0, 100.8));
        WtxStructureFilter.Decision d = WtxStructureFilter.evaluate("LONG", candles, ATR, LOOKBACK, SWEEP_BUFFER);
        assertTrue(d.allows());
        assertEquals(WtxStructureFilter.StructureReason.BULLISH_RECLAIM, d.reason());
    }

    @Test
    void bearishReject_allowsShort() {
        List<Candle> candles = buildFlatHistory(100, LOOKBACK + 2);
        // Last bar: close < open, close < midBodyPrev, high >= priorHigh - 0.25*ATR
        candles.set(candles.size() - 1, candle(101, 101, 99, 99.2));
        WtxStructureFilter.Decision d = WtxStructureFilter.evaluate("SHORT", candles, ATR, LOOKBACK, SWEEP_BUFFER);
        assertTrue(d.allows());
        assertEquals(WtxStructureFilter.StructureReason.BEARISH_REJECT, d.reason());
    }

    @Test
    void plainBar_blocksLong() {
        List<Candle> candles = buildFlatHistory(100, LOOKBACK + 2);
        // Last bar: ordinary inside-range bar (no sweep, no reclaim)
        candles.set(candles.size() - 1, candle(100, 100.3, 99.8, 100.1));
        WtxStructureFilter.Decision d = WtxStructureFilter.evaluate("LONG", candles, ATR, LOOKBACK, SWEEP_BUFFER);
        assertFalse(d.allows());
        assertEquals(WtxStructureFilter.StructureReason.BLOCKED, d.reason());
    }

    private static List<Candle> buildFlatHistory(double basePrice, int count) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Each prior bar oscillates between 99 and 101 → priorLow=99, priorHigh=101
            double open = basePrice;
            double close = basePrice;
            double high = basePrice + 1;
            double low = basePrice - 1;
            out.add(candle(open, high, low, close));
        }
        return out;
    }

    private static Candle candle(double open, double high, double low, double close) {
        return new Candle(Instrument.MCL, "10m", Instant.now(),
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                100L);
    }
}
