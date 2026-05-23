package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WtxRsiSwingBiasDetectorTest {

    @Test
    void higher_highs_and_higher_lows_resolve_to_bullish() {
        // Two fractal lows at indices 2 (95) and 7 (97 — higher),
        // two fractal highs at indices 4 (110) and 9 (112 — higher).
        List<Candle> candles = candles(new double[][]{
                {100, 102, 99,  101},
                {101, 103, 100, 102},
                {102, 103, 95,  98},   // pivot LOW 1 (95)
                {98,  102, 96,  101},
                {101, 110, 100, 108},  // pivot HIGH 1 (110)
                {108, 109, 105, 106},
                {106, 107, 102, 103},
                {103, 105, 97,  99},   // pivot LOW 2 (97 > 95 → HL)
                {99,  104, 98,  103},
                {103, 112, 102, 110},  // pivot HIGH 2 (112 > 110 → HH)
                {110, 111, 108, 109},
                {109, 110, 107, 108}
        });
        assertEquals(WtxRsiSwingBias.BULLISH, WtxRsiSwingBiasDetector.detect(candles, 2, 50));
    }

    @Test
    void lower_highs_and_lower_lows_resolve_to_bearish() {
        List<Candle> candles = candles(new double[][]{
                {100, 102, 99,  100},
                {100, 102, 99,  101},
                {101, 110, 100, 105},  // HIGH 1 (110)
                {105, 106, 102, 103},
                {103, 104, 95,  97},   // LOW 1 (95)
                {97,  100, 96,  99},
                {99,  100, 97,  98},
                {98,  108, 97,  102},  // HIGH 2 (108 < 110 → LH)
                {102, 103, 100, 101},
                {101, 102, 93,  95},   // LOW 2 (93 < 95 → LL)
                {95,  98,  94,  96},
                {96,  97,  95,  96}
        });
        assertEquals(WtxRsiSwingBias.BEARISH, WtxRsiSwingBiasDetector.detect(candles, 2, 50));
    }

    @Test
    void mixed_signals_resolve_to_neutral() {
        // HH + LL — chop
        List<Candle> candles = candles(new double[][]{
                {100, 102, 99,  100},
                {100, 102, 99,  101},
                {101, 110, 100, 105},  // HIGH 1 (110)
                {105, 106, 102, 103},
                {103, 104, 95,  97},   // LOW 1 (95)
                {97,  100, 96,  99},
                {99,  100, 97,  98},
                {98,  115, 97,  108},  // HIGH 2 (115 > 110 → HH)
                {108, 110, 105, 106},
                {106, 107, 90,  92},   // LOW 2 (90 < 95 → LL)
                {92,  95,  91,  93},
                {93,  94,  90,  91}
        });
        assertEquals(WtxRsiSwingBias.NEUTRAL, WtxRsiSwingBiasDetector.detect(candles, 2, 50));
    }

    @Test
    void warmup_returns_neutral() {
        List<Candle> candles = candles(new double[][]{
                {100, 101, 99, 100},
                {100, 101, 99, 100}
        });
        assertEquals(WtxRsiSwingBias.NEUTRAL, WtxRsiSwingBiasDetector.detect(candles, 2, 20));
    }

    private static List<Candle> candles(double[][] ohlc) {
        List<Candle> out = new ArrayList<>(ohlc.length);
        Instant ts = Instant.parse("2025-01-02T14:30:00Z");
        for (int i = 0; i < ohlc.length; i++) {
            double[] r = ohlc[i];
            out.add(new Candle(
                    Instrument.MNQ, "5m",
                    ts.plus(i * 5L, ChronoUnit.MINUTES),
                    BigDecimal.valueOf(r[0]),
                    BigDecimal.valueOf(r[1]),
                    BigDecimal.valueOf(r[2]),
                    BigDecimal.valueOf(r[3]),
                    1000L));
        }
        return out;
    }
}
