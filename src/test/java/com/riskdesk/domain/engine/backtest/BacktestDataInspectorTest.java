package com.riskdesk.domain.engine.backtest;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BacktestDataInspectorTest {

    @Test
    void inspect_sortsDedupesAndReportsWarnings() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T01:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T02:35:00Z");

        List<Candle> raw = List.of(
            candle(t1, 101, 102, 100, 101),
            candle(t0, 100, 101, 99, 100),
            candle(t1, 101, 102, 100, 101.5),
            candle(t2, 102, 103, 101, 102)
        );

        BacktestDataInspector.InspectionResult result = BacktestDataInspector.inspect(
            Instrument.MNQ,
            "1h",
            raw,
            t1,
            5
        );

        assertEquals(3, result.candles().size());
        assertEquals(t0, result.candles().get(0).getTimestamp());
        assertEquals(1, result.audit().duplicateCandles());
        assertEquals(1, result.audit().outOfOrderPairs());
        assertEquals(1, result.audit().misalignedCandles());
        assertEquals(1, result.audit().availableWarmupBars());
        assertFalse(result.audit().sufficientWarmup());
        assertFalse(result.audit().warnings().isEmpty());
    }

    @Test
    void inspect_shiftsEvaluationStartWhenWarmupIsMissing() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        List<Candle> candles = List.of(
            candle(start, 100, 101, 99, 100),
            candle(start.plus(1, java.time.temporal.ChronoUnit.HOURS), 101, 102, 100, 101),
            candle(start.plus(2, java.time.temporal.ChronoUnit.HOURS), 102, 103, 101, 102),
            candle(start.plus(3, java.time.temporal.ChronoUnit.HOURS), 103, 104, 102, 103),
            candle(start.plus(4, java.time.temporal.ChronoUnit.HOURS), 104, 105, 103, 104),
            candle(start.plus(5, java.time.temporal.ChronoUnit.HOURS), 105, 106, 104, 105)
        );

        BacktestDataInspector.InspectionResult result = BacktestDataInspector.inspect(
            Instrument.MNQ,
            "1h",
            candles,
            start,
            3
        );

        assertEquals(start.plus(3, java.time.temporal.ChronoUnit.HOURS), result.effectiveEvaluationStart());
        assertEquals(3, result.audit().availableWarmupBars());
        assertEquals(3, result.audit().evaluatedCandles());
        assertTrue(result.audit().sufficientWarmup());
        assertTrue(result.audit().adjustedEvaluationStart());
    }

    @Test
    void inspect_acceptsExpectedMnqHourlySessionOffsetsAndGaps() {
        List<Candle> candles = List.of(
            candle(Instant.parse("2026-03-18T20:00:00Z"), 100, 101, 99, 100),
            candle(Instant.parse("2026-03-19T13:30:00Z"), 101, 102, 100, 101),
            candle(Instant.parse("2026-03-19T14:00:00Z"), 102, 103, 101, 102),
            candle(Instant.parse("2026-03-19T15:00:00Z"), 103, 104, 102, 103)
        );

        BacktestDataInspector.InspectionResult result = BacktestDataInspector.inspect(
            Instrument.MNQ,
            "1h",
            candles,
            candles.get(0).getTimestamp(),
            0
        );

        assertEquals(0, result.audit().misalignedCandles());
        assertEquals(0, result.audit().suspiciousGapCount());
    }

    @Test
    void inspect_trimsTrailingCandlesAfterSuspiciousGap() {
        List<Candle> candles = List.of(
            candle(Instant.parse("2026-03-24T07:00:00Z"), 100, 101, 99, 100),
            candle(Instant.parse("2026-03-24T08:00:00Z"), 101, 102, 100, 101),
            candle(Instant.parse("2026-03-24T09:00:00Z"), 102, 103, 101, 102),
            candle(Instant.parse("2026-03-24T15:00:00Z"), 103, 104, 102, 103)
        );

        BacktestDataInspector.InspectionResult result = BacktestDataInspector.inspect(
            Instrument.MNQ,
            "1h",
            candles,
            candles.get(0).getTimestamp(),
            0
        );

        assertEquals(3, result.candles().size());
        assertEquals(Instant.parse("2026-03-24T09:00:00Z"), result.candles().get(2).getTimestamp());
        assertEquals(0, result.audit().suspiciousGapCount());
        assertTrue(result.audit().warnings().stream().anyMatch(w -> w.contains("Removed 1 trailing candle")));
    }

    @Test
    void floorToTimeframe_roundsDownToBoundary() {
        Instant instant = Instant.parse("2026-03-23T14:47:12Z");

        assertEquals(Instant.parse("2026-03-23T14:00:00Z"), BacktestDataInspector.floorToTimeframe(instant, "1h"));
        assertEquals(Instant.parse("2026-03-23T14:40:00Z"), BacktestDataInspector.floorToTimeframe(instant, "10m"));
    }

    private static Candle candle(Instant timestamp, double open, double high, double low, double close) {
        return new Candle(
            Instrument.MNQ,
            "1h",
            timestamp,
            BigDecimal.valueOf(open),
            BigDecimal.valueOf(high),
            BigDecimal.valueOf(low),
            BigDecimal.valueOf(close),
            100
        );
    }
}
