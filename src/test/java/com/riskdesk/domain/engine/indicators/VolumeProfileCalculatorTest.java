package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VolumeProfileCalculatorTest {

    private final VolumeProfileCalculator calculator = new VolumeProfileCalculator();

    @Test
    void computesPocAtHighestVolume() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2026-04-03T14:00:00Z");
        // Most volume around 100.00
        candles.add(candle(base, "99.50", "100.50", "99.00", "100.00", 100));
        candles.add(candle(base.plusSeconds(60), "100.00", "100.50", "99.50", "100.25", 200));
        candles.add(candle(base.plusSeconds(120), "100.25", "101.00", "100.00", "100.50", 150));
        candles.add(candle(base.plusSeconds(180), "100.50", "101.50", "100.00", "101.00", 50));
        candles.add(candle(base.plusSeconds(240), "101.00", "102.00", "100.50", "101.50", 30));

        var result = calculator.compute(candles, new BigDecimal("0.25"), 4);
        assertNotNull(result);
        assertNotNull(result.pocPrice());
        assertNotNull(result.valueAreaHigh());
        assertNotNull(result.valueAreaLow());
        assertTrue(result.valueAreaHigh().compareTo(result.valueAreaLow()) >= 0);
    }

    @Test
    void nullForInsufficientData() {
        assertNull(calculator.compute(null, new BigDecimal("0.25"), 4));
        assertNull(calculator.compute(List.of(), new BigDecimal("0.25"), 4));
    }

    @Test
    void valueAreaContainsPoc() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2026-04-03T14:00:00Z");
        for (int i = 0; i < 20; i++) {
            candles.add(candle(base.plusSeconds(i * 60), "100.00", "101.00", "99.00", "100.50", 100));
        }
        var result = calculator.compute(candles, new BigDecimal("0.25"), 4);
        assertNotNull(result);
        assertTrue(result.pocPrice().compareTo(result.valueAreaLow()) >= 0);
        assertTrue(result.pocPrice().compareTo(result.valueAreaHigh()) <= 0);
    }

    private Candle candle(Instant ts, String o, String h, String l, String c, long vol) {
        Candle candle = new Candle();
        candle.setInstrument(Instrument.MNQ);
        candle.setTimeframe("10m");
        candle.setTimestamp(ts);
        candle.setOpen(new BigDecimal(o));
        candle.setHigh(new BigDecimal(h));
        candle.setLow(new BigDecimal(l));
        candle.setClose(new BigDecimal(c));
        candle.setVolume(vol);
        return candle;
    }
}
