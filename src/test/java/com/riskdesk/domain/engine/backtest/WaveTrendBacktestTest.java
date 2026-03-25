package com.riskdesk.domain.engine.backtest;

import com.riskdesk.application.service.EntryFilterService;
import com.riskdesk.application.service.HigherTimeframeLevelService;
import com.riskdesk.application.service.MarketStructureService;
import com.riskdesk.domain.engine.smc.MarketStructure;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class WaveTrendBacktestTest {

    @Test
    void evaluationStart_excludesTradesBeforeRequestedWindow() {
        List<Candle> candles = waveCandles(320, Instant.parse("2026-01-01T00:00:00Z"));
        Instant evaluationStart = candles.get(200).getTimestamp();

        BacktestResult result = new WaveTrendBacktest()
            .useCompra(true)
            .useVenta(true)
            .useCompra1(true)
            .useVenta1(true)
            .maxPyramiding(1)
            .entryOnSignal(1)
            .evaluationStart(evaluationStart)
            .run(candles);

        assertEquals(candles.size() - 200, result.totalCandles());
        assertTrue(result.trades().stream().allMatch(t -> !t.entryTime().isBefore(evaluationStart)));
        assertTrue(result.signals().stream().allMatch(s -> !Instant.parse(s.time()).isBefore(evaluationStart)));
    }

    @Test
    void closeEntry_doesNotUseSameBarLowHighForStopLoss() {
        List<Candle> baseCandles = waveCandles(320, Instant.parse("2026-01-01T00:00:00Z"));
        WaveTrendBacktest seededEngine = new WaveTrendBacktest()
            .useCompra(true)
            .useVenta(true)
            .useCompra1(true)
            .useVenta1(true)
            .maxPyramiding(1)
            .entryOnSignal(1);

        BacktestResult seeded = seededEngine.run(baseCandles);
        assertFalse(seeded.signals().isEmpty(), "Synthetic wave series should generate at least one WT signal");

        BacktestResult.SignalDebug firstSignal = seeded.signals().get(0);
        List<Candle> mutated = new ArrayList<>(baseCandles);
        int signalIndex = indexOf(mutated, Instant.parse(firstSignal.time()));
        Candle signalCandle = mutated.get(signalIndex);

        if ("LONG".equals(firstSignal.type())) {
            mutated.set(signalIndex, candle(signalCandle.getTimestamp(), signalCandle.getOpen().doubleValue(), signalCandle.getHigh().doubleValue(), signalCandle.getClose().doubleValue() - 25, signalCandle.getClose().doubleValue()));
            Candle next = mutated.get(signalIndex + 1);
            mutated.set(signalIndex + 1, candle(next.getTimestamp(), next.getOpen().doubleValue(), next.getHigh().doubleValue(), Math.max(next.getLow().doubleValue(), next.getClose().doubleValue() - 0.25), next.getClose().doubleValue()));
        } else {
            mutated.set(signalIndex, candle(signalCandle.getTimestamp(), signalCandle.getOpen().doubleValue(), signalCandle.getClose().doubleValue() + 25, signalCandle.getLow().doubleValue(), signalCandle.getClose().doubleValue()));
            Candle next = mutated.get(signalIndex + 1);
            mutated.set(signalIndex + 1, candle(next.getTimestamp(), next.getOpen().doubleValue(), Math.min(next.getHigh().doubleValue(), next.getClose().doubleValue() + 0.25), next.getLow().doubleValue(), next.getClose().doubleValue()));
        }

        BacktestResult result = new WaveTrendBacktest()
            .useCompra(true)
            .useVenta(true)
            .useCompra1(true)
            .useVenta1(true)
            .maxPyramiding(1)
            .entryOnSignal(1)
            .stopLossPoints(1)
            .run(mutated);

        assertTrue(result.totalTrades() > 0);
        assertTrue(result.trades().stream().noneMatch(t ->
            "STOP_LOSS".equals(t.exitReason()) && t.entryTime().equals(t.exitTime())
        ));
    }

    @Test
    void bollingerTakeProfit_exitsWhenBandIsTouchedBeforeSessionClose() {
        List<Candle> baseCandles = waveCandles(320, Instant.parse("2026-01-01T00:00:00Z"));
        BacktestResult seeded = new WaveTrendBacktest()
            .useCompra(true)
            .useVenta(true)
            .useCompra1(true)
            .useVenta1(true)
            .maxPyramiding(1)
            .entryOnSignal(1)
            .run(baseCandles);

        assertFalse(seeded.signals().isEmpty(), "Synthetic wave series should generate at least one WT signal");

        BacktestResult.SignalDebug firstSignal = seeded.signals().get(0);
        List<Candle> mutated = new ArrayList<>(baseCandles);
        int signalIndex = indexOf(mutated, Instant.parse(firstSignal.time()));
        Candle takeProfitCandle = mutated.get(signalIndex + 1);

        if ("LONG".equals(firstSignal.type())) {
            mutated.set(signalIndex + 1, candle(
                takeProfitCandle.getTimestamp(),
                takeProfitCandle.getOpen().doubleValue(),
                takeProfitCandle.getClose().doubleValue() + 30,
                takeProfitCandle.getLow().doubleValue(),
                takeProfitCandle.getClose().doubleValue()
            ));
        } else {
            mutated.set(signalIndex + 1, candle(
                takeProfitCandle.getTimestamp(),
                takeProfitCandle.getOpen().doubleValue(),
                takeProfitCandle.getHigh().doubleValue(),
                takeProfitCandle.getClose().doubleValue() - 30,
                takeProfitCandle.getClose().doubleValue()
            ));
        }

        BacktestResult result = new WaveTrendBacktest()
            .useCompra(true)
            .useVenta(true)
            .useCompra1(true)
            .useVenta1(true)
            .maxPyramiding(1)
            .entryOnSignal(1)
            .bollingerTakeProfit(true)
            .bollingerLength(20)
            .closeEndOfDay(true)
            .run(mutated);

        assertTrue(result.trades().stream().anyMatch(t -> "BB_TAKE_PROFIT".equals(t.exitReason())));
    }

    @Test
    void sessionCloseDetection_usesObservedIntradayGapForEndOfDay() throws Exception {
        List<Candle> candles = List.of(
            candle(Instant.parse("2026-03-24T14:00:00Z"), 100, 101, 99, 100),
            candle(Instant.parse("2026-03-24T15:00:00Z"), 101, 102, 100, 101),
            candle(Instant.parse("2026-03-24T17:00:00Z"), 102, 103, 101, 102)
        );

        WaveTrendBacktest engine = new WaveTrendBacktest().closeEndOfDay(true);

        Method method = WaveTrendBacktest.class.getDeclaredMethod("resolveSessionCloseReason", List.class, int.class);
        method.setAccessible(true);

        assertEquals("END_OF_DAY", method.invoke(engine, candles, 1));
    }

    @Test
    void sessionCloseDetection_usesWeekendGapForEndOfWeek() throws Exception {
        List<Candle> candles = List.of(
            candle(Instant.parse("2026-03-20T15:00:00Z"), 100, 101, 99, 100),
            candle(Instant.parse("2026-03-22T17:00:00Z"), 101, 102, 100, 101)
        );

        WaveTrendBacktest engine = new WaveTrendBacktest().closeEndOfWeek(true);

        Method method = WaveTrendBacktest.class.getDeclaredMethod("resolveSessionCloseReason", List.class, int.class);
        method.setAccessible(true);

        assertEquals("END_OF_WEEK", method.invoke(engine, candles, 0));
    }

    @Test
    void smcFilter_keepsOriginalUntouchedAndRejectsFilteredTradesWhenNoLevelsExist() {
        List<Candle> candles = waveCandles(320, Instant.parse("2026-01-01T00:00:00Z"));

        BacktestResult original = new WaveTrendBacktest()
            .useCompra(true)
            .useVenta(true)
            .useCompra1(true)
            .useVenta1(true)
            .maxPyramiding(1)
            .entryOnSignal(1)
            .run(candles);

        EntryFilterService.Config smcConfig = new EntryFilterService.Config(
            true,
            true,
            HigherTimeframeLevelService.ThresholdMode.ATR,
            0.25,
            14,
            0.25,
            false,
            false,
            true,
            true,
            false,
            1,
            0,
            true
        );

        BacktestResult filtered = new WaveTrendBacktest()
            .useCompra(true)
            .useVenta(true)
            .useCompra1(true)
            .useVenta1(true)
            .maxPyramiding(1)
            .entryOnSignal(1)
            .enableSmcFilter(true)
            .smcFilterConfig(smcConfig)
            .htfLevelIndex(HigherTimeframeLevelService.LevelIndex.empty())
            .htfStructureIndex(MarketStructureService.StructureContextIndex.empty())
            .debug(true)
            .run(candles);

        assertTrue(original.totalTrades() > 0, "Original strategy should still trade");
        assertEquals(0, filtered.totalTrades(), "Filtered strategy should reject entries when no HTF levels exist");
        assertTrue(filtered.debugEvents().stream().anyMatch(e -> e.reason().contains("no confirmed HTF support") || e.reason().contains("no confirmed HTF resistance")));
    }

    @Test
    void smcFilter_acceptsOriginalSignalWhenNearSupportWithBullishStructure() {
        List<Candle> candles = waveCandles(320, Instant.parse("2026-01-01T00:00:00Z"));
        BacktestResult seeded = new WaveTrendBacktest()
            .useCompra(true)
            .useVenta(true)
            .useCompra1(true)
            .useVenta1(true)
            .maxPyramiding(1)
            .entryOnSignal(1)
            .run(candles);

        BacktestResult.SignalDebug firstLong = seeded.signals().stream()
            .filter(s -> "LONG".equals(s.type()))
            .findFirst()
            .orElseThrow();
        Instant signalTime = Instant.parse(firstLong.time());

        HigherTimeframeLevelService.LevelIndex levelIndex = new HigherTimeframeLevelService.LevelIndex(
            List.of(
                new HigherTimeframeLevelService.HtfLevel(
                    HigherTimeframeLevelService.LevelType.SUPPORT,
                    BigDecimal.valueOf(firstLong.price() - 0.05),
                    signalTime.minus(4, ChronoUnit.HOURS),
                    signalTime.minus(1, ChronoUnit.HOURS),
                    "1h"
                )
            ),
            List.of()
        );
        MarketStructureService.StructureContextIndex structureIndex = new MarketStructureService.StructureContextIndex(
            List.of(
                new MarketStructureService.StructureEvent(
                    MarketStructure.StructureType.BOS,
                    MarketStructure.Trend.BULLISH,
                    BigDecimal.valueOf(firstLong.price() - 0.05),
                    1,
                    signalTime.minus(30, ChronoUnit.MINUTES),
                    new MarketStructureService.ConfirmedSwing(
                        MarketStructure.SwingType.LOW,
                        BigDecimal.valueOf(firstLong.price() - 0.05),
                        1,
                        signalTime.minus(5, ChronoUnit.HOURS),
                        signalTime.minus(1, ChronoUnit.HOURS),
                        "1h"
                    ),
                    "1h"
                )
            )
        );

        EntryFilterService.Config smcConfig = new EntryFilterService.Config(
            true,
            true,
            HigherTimeframeLevelService.ThresholdMode.POINTS,
            0.25,
            14,
            0.25,
            true,
            false,
            true,
            true,
            false,
            2,
            0,
            true
        );

        BacktestResult filtered = new WaveTrendBacktest()
            .useCompra(true)
            .useVenta(true)
            .useCompra1(true)
            .useVenta1(true)
            .maxPyramiding(1)
            .entryOnSignal(1)
            .enableSmcFilter(true)
            .smcFilterConfig(smcConfig)
            .htfLevelIndex(levelIndex)
            .htfStructureIndex(structureIndex)
            .debug(true)
            .run(candles);

        assertTrue(filtered.totalTrades() > 0);
        assertTrue(filtered.debugEvents().stream().anyMatch(e -> e.reason().contains("accepted: existing signal + close near HTF support")));
    }

    private static int indexOf(List<Candle> candles, Instant timestamp) {
        for (int i = 0; i < candles.size(); i++) {
            if (candles.get(i).getTimestamp().equals(timestamp)) {
                return i;
            }
        }
        fail("Timestamp not found in candle set: " + timestamp);
        return -1;
    }

    private static List<Candle> waveCandles(int count, Instant start) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double anchor = 100 + Math.sin(i / 5.0) * 8 + Math.cos(i / 11.0) * 4;
            double open = anchor + Math.sin(i / 3.0);
            double close = anchor + Math.cos(i / 4.0);
            double high = Math.max(open, close) + 1.5 + Math.abs(Math.sin(i / 7.0));
            double low = Math.min(open, close) - 1.5 - Math.abs(Math.cos(i / 6.0));
            candles.add(candle(start.plus(i, ChronoUnit.HOURS), open, high, low, close));
        }
        return candles;
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
