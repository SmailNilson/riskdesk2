package com.riskdesk.domain.engine.smc;

import com.riskdesk.domain.engine.smc.EqualLevelDetector.*;
import com.riskdesk.domain.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates EQH/EQL detection matching LuxAlgo equal-level logic.
 * <p>
 * Uses lookback=2 for concise test data and threshold=0.5% to allow
 * clear pass/fail cases on the hardcoded OHLC.
 */
class EqualLevelDetectorTest {

    private static Candle bar(int index, double high, double low, double close) {
        Candle c = new Candle();
        c.setTimestamp(Instant.EPOCH.plusSeconds(index * 60L));
        c.setOpen(BigDecimal.valueOf((high + low) / 2));
        c.setHigh(BigDecimal.valueOf(high));
        c.setLow(BigDecimal.valueOf(low));
        c.setClose(BigDecimal.valueOf(close));
        return c;
    }

    /**
     * Two swing highs at similar prices (100.0 and 100.3) with 0.5% threshold.
     * Difference = 0.3%, avg ≈ 100.15 → 0.3/100.15*100 = 0.30% < 0.5% → EQH.
     *
     * Pattern: rise → peak1 → drop → rise → peak2 → drop
     * With lookback=2: bar 2 is confirmed HIGH (bars 3,4 lower),
     *                   bar 7 is confirmed HIGH (bars 8,9 lower).
     */
    private static final double[][] EQH_DATA = {
            //  H       L      C
            {  98,     96,    97   },  // 0
            {  99,     97,    98   },  // 1
            { 100,     98,    99   },  // 2  ← swing HIGH (100)
            {  98,     96,    97   },  // 3
            {  99,     95,    96   },  // 4  (H=99 > bar3 H=98, prevents bar3 swing)
            {  97,     95,    96   },  // 5
            {  99,     97,    98   },  // 6
            { 100.3,   98,    99   },  // 7  ← swing HIGH (100.3) — equal to bar 2
            {  99,     97,    98   },  // 8
            {  97,     95,    96   },  // 9
    };

    /**
     * Two swing lows at similar prices (95.0 and 95.2) with 0.5% threshold.
     * Difference = 0.2%, avg ≈ 95.1 → 0.2/95.1*100 = 0.21% < 0.5% → EQL.
     *
     * Pattern: drop → trough1 → rise → drop → trough2 → rise
     * With lookback=2: bar 2 is confirmed LOW (bars 3,4 higher),
     *                   bar 7 is confirmed LOW (bars 8,9 higher).
     */
    private static final double[][] EQL_DATA = {
            //  H       L      C
            { 100,     97,    98   },  // 0
            {  98,     96,    97   },  // 1
            {  96,     95,    95.5 },  // 2  ← swing LOW (95)
            {  98,     96,    97   },  // 3
            { 100,     98,    99   },  // 4  rise
            {  98,     96,    97   },  // 5
            {  97,     95.5,  96   },  // 6
            {  96.5,   95.2,  95.5 },  // 7  ← swing LOW (95.2) — equal to bar 2
            {  98,     96,    97   },  // 8
            { 100,     98,    99   },  // 9
    };

    /**
     * Two swing highs far apart (100 and 103) with 0.5% threshold.
     * Difference = 3%, avg ≈ 101.5 → 3/101.5*100 = 2.96% > 0.5% → no EQH.
     */
    private static final double[][] NO_EQH_DATA = {
            //  H       L      C
            {  98,     96,    97   },  // 0
            {  99,     97,    98   },  // 1
            { 100,     98,    99   },  // 2  ← swing HIGH (100)
            {  99,     97,    98   },  // 3
            {  97,     95,    96   },  // 4
            {  99,     97,    98   },  // 5
            { 101,     99,   100   },  // 6
            { 103,    100,   102   },  // 7  ← swing HIGH (103) — NOT equal
            { 101,     99,   100   },  // 8
            {  99,     97,    98   },  // 9
    };

    private List<Candle> toCandles(double[][] data) {
        List<Candle> candles = new ArrayList<>(data.length);
        for (int i = 0; i < data.length; i++) {
            candles.add(bar(i, data[i][0], data[i][1], data[i][2]));
        }
        return candles;
    }

    @Test
    void detectsEqualHighs() {
        EqualLevelDetector detector = new EqualLevelDetector(2, 0.5);
        List<LiquidityPool> results = detector.detectPools(toCandles(EQH_DATA));

        List<LiquidityPool> eqh = results.stream()
                .filter(e -> e.type() == EqualType.EQH).toList();

        assertEquals(1, eqh.size(), "Expected 1 EQH");
        LiquidityPool eq = eqh.get(0);
        assertEquals(2, eq.firstBar());
        assertEquals(7, eq.lastBar());
        assertEquals(2, eq.touchCount());
        assertEquals((100.0 + 100.3) / 2, eq.price(), 0.001);
    }

    @Test
    void detectsEqualLows() {
        EqualLevelDetector detector = new EqualLevelDetector(2, 0.5);
        List<LiquidityPool> results = detector.detectPools(toCandles(EQL_DATA));

        List<LiquidityPool> eql = results.stream()
                .filter(e -> e.type() == EqualType.EQL).toList();

        assertEquals(1, eql.size(), "Expected 1 EQL");
        LiquidityPool eq = eql.get(0);
        assertEquals(2, eq.firstBar());
        assertEquals(7, eq.lastBar());
        assertEquals(2, eq.touchCount());
    }

    @Test
    void noSignalOutsideThreshold() {
        EqualLevelDetector detector = new EqualLevelDetector(2, 0.5);
        List<LiquidityPool> results = detector.detectPools(toCandles(NO_EQH_DATA));

        List<LiquidityPool> eqh = results.stream()
                .filter(e -> e.type() == EqualType.EQH).toList();

        assertTrue(eqh.isEmpty(), "Should not detect EQH when price diff exceeds threshold");
    }

    @Test
    void insufficientDataReturnsEmpty() {
        EqualLevelDetector detector = new EqualLevelDetector(5, 0.5);
        List<Candle> tiny = toCandles(new double[][]{{10, 8, 9}, {12, 9, 11}});

        assertTrue(detector.detect(tiny).isEmpty());
    }

    @Test
    void widerThresholdCatchesMorePairs() {
        // With 0.5% threshold: NO_EQH_DATA yields 0 EQH (diff=2.96%)
        // With 5.0% threshold: same data yields 1 EQH
        EqualLevelDetector wide = new EqualLevelDetector(2, 5.0);
        List<LiquidityPool> results = wide.detectPools(toCandles(NO_EQH_DATA));

        List<LiquidityPool> eqh = results.stream()
                .filter(e -> e.type() == EqualType.EQH).toList();
        assertEquals(1, eqh.size(), "Wider threshold should catch the pair");
    }

    @Test
    void clustersNearbyPivotsIntoSinglePool() {
        double[][] data = {
                {98, 96, 97},
                {99, 97, 98},
                {100.0, 98, 99},
                {98, 96, 97},
                {97, 95, 96},
                {99, 97, 98},
                {100.2, 98, 99},
                {99, 97, 98},
                {97, 95, 96},
                {99, 97, 98},
                {100.1, 98, 99},
                {99, 97, 98},
                {97, 95, 96},
        };

        EqualLevelDetector detector = new EqualLevelDetector(2, 0.5);
        List<LiquidityPool> results = detector.detectPools(toCandles(data));

        assertTrue(
                results.stream().anyMatch(pool -> pool.type() == EqualType.EQH && pool.touchCount() == 3),
                "Expected one EQH pool aggregating the 3 nearby pivots"
        );
    }

    @Test
    void excludesSweptPools() {
        double[][] data = {
                {98, 96, 97},
                {99, 97, 98},
                {100.0, 98, 99},
                {98, 96, 97},
                {99, 95, 96},
                {97, 95, 96},
                {99, 97, 98},
                {100.2, 98, 99},
                {99, 97, 98},
                {97, 95, 96},
                {101.5, 99, 100},
                {99, 97, 98},
                {98, 96, 97},
        };

        EqualLevelDetector detector = new EqualLevelDetector(2, 0.5);
        List<LiquidityPool> results = detector.detectPools(toCandles(data));
        assertTrue(results.stream().noneMatch(pool -> Math.abs(pool.price() - 100.1) < 0.5),
                "Swept liquidity around the original equal highs should be hidden");
    }
}
