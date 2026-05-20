package com.riskdesk.domain.marketdata.model;

import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TickAggregationTest {

    @Test
    void recordFieldAccess() {
        var now = Instant.now();
        var agg = new TickAggregation(
            Instrument.MCL,
            1500, 800, 700, 3200,
            65.2,
            TickAggregation.TREND_RISING,
            false, null,
            now.minusSeconds(300), now,
            TickAggregation.SOURCE_REAL_TICKS,
            72.50, 71.80,
            71.90, 72.30
        );

        assertEquals(Instrument.MCL, agg.instrument());
        assertEquals(1500, agg.buyVolume());
        assertEquals(800, agg.sellVolume());
        assertEquals(700, agg.delta());
        assertEquals(3200, agg.cumulativeDelta());
        assertEquals(65.2, agg.buyRatioPct(), 0.01);
        assertEquals("RISING", agg.deltaTrend());
        assertFalse(agg.divergenceDetected());
        assertNull(agg.divergenceType());
        assertEquals("REAL_TICKS", agg.source());
        assertEquals(72.50, agg.highPrice(), 0.001);
        assertEquals(71.80, agg.lowPrice(), 0.001);
    }

    @Test
    void bearishDivergenceRecord() {
        var now = Instant.now();
        var agg = new TickAggregation(
            Instrument.MGC,
            600, 900, -300, -1200,
            40.0,
            TickAggregation.TREND_FALLING,
            true, TickAggregation.DIVERGENCE_BEARISH,
            now.minusSeconds(300), now,
            TickAggregation.SOURCE_CLV_ESTIMATED,
            Double.NaN, Double.NaN,
            Double.NaN, Double.NaN
        );

        assertTrue(agg.divergenceDetected());
        assertEquals("BEARISH_DIVERGENCE", agg.divergenceType());
        assertEquals("CLV_ESTIMATED", agg.source());
    }

    @Test
    void constants() {
        assertEquals("RISING", TickAggregation.TREND_RISING);
        assertEquals("FALLING", TickAggregation.TREND_FALLING);
        assertEquals("FLAT", TickAggregation.TREND_FLAT);
        assertEquals("REAL_TICKS", TickAggregation.SOURCE_REAL_TICKS);
        assertEquals("CLV_ESTIMATED", TickAggregation.SOURCE_CLV_ESTIMATED);
        assertEquals("BEARISH_DIVERGENCE", TickAggregation.DIVERGENCE_BEARISH);
        assertEquals("BULLISH_DIVERGENCE", TickAggregation.DIVERGENCE_BULLISH);
    }
}
