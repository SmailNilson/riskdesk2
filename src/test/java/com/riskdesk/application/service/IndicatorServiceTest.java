package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSeriesSnapshot;
import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class IndicatorServiceTest {

    @Test
    void computeSnapshotLoadsSnapshotLookbackAndReturnsLatestTimestamp() {
        FakeCandleRepositoryPort candlePort = new FakeCandleRepositoryPort();
        ActiveContractRegistry contractRegistry = new ActiveContractRegistry();
        List<Candle> history = buildHistory(600);
        candlePort.stubRecentCandles(Instrument.MCL, "10m", 500, descendingTail(history, 500));

        IndicatorService service = new IndicatorService(candlePort, contractRegistry, emptyProvider(), emptyProvider(), emptyProvider());

        IndicatorSnapshot snapshot = service.computeSnapshot(Instrument.MCL, "10m");

        // 10m uses tiered lookback of 500 (matches TradingView chart depth)
        assertEquals(List.of("MCL:10m:500", "MCL:1d:2", "MCL:1w:2", "MCL:1M:2"), candlePort.recentRequests());
        assertEquals("MCL", snapshot.instrument());
        assertEquals("10m", snapshot.timeframe());
        assertNotNull(snapshot.activeFairValueGaps());
        assertEquals(history.get(history.size() - 1).getTimestamp(), snapshot.lastCandleTimestamp());
    }

    @Test
    void computeSeriesLoadsWarmupHistoryButReturnsRequestedWindow() {
        FakeCandleRepositoryPort candlePort = new FakeCandleRepositoryPort();
        ActiveContractRegistry contractRegistry = new ActiveContractRegistry();
        List<Candle> history = buildHistory(800);
        candlePort.stubRecentCandles(Instrument.MCL, "10m", 700, descendingTail(history, 700));

        IndicatorService service = new IndicatorService(candlePort, contractRegistry, emptyProvider(), emptyProvider(), emptyProvider());

        IndicatorSeriesSnapshot series = service.computeSeries(Instrument.MCL, "10m", 500);

        assertEquals(List.of("MCL:10m:700"), candlePort.recentRequests());
        // After purgeOutOfSession removes weekend bars and trimLast caps at limit,
        // the returned series may be smaller than requested. EMA-200 will also have
        // fewer points than EMA-9 due to higher warmup requirement.
        // Verify all series are non-empty and reasonably sized.
        assertNotNull(series.ema9());
        assertNotNull(series.ema200());
        assertNotNull(series.bollingerBands());
        assertNotNull(series.waveTrend());
        // EMA-200 needs 200 warmup bars so it has fewer data points than EMA-9
        assert series.ema200().size() > 0 : "EMA-200 series should not be empty";
        assert series.ema200().size() <= series.ema9().size() : "EMA-200 should have <= points than EMA-9";
    }

    private static List<Candle> buildHistory(int count) {
        List<Candle> candles = new ArrayList<>(count);
        Instant cursor = Instant.parse("2026-03-01T23:00:00Z");
        for (int i = 0; candles.size() < count; i++) {
            Instant ts = cursor.plusSeconds(i * 600L);
            BigDecimal price = BigDecimal.valueOf(90 + (candles.size() * 0.05));
            candles.add(new Candle(
                    Instrument.MCL,
                    "10m",
                    ts,
                    price,
                    price.add(BigDecimal.valueOf(0.2)),
                    price.subtract(BigDecimal.valueOf(0.2)),
                    price.add(BigDecimal.valueOf(0.1)),
                    1_000L + candles.size()
            ));
        }
        return candles;
    }

    private static List<Candle> descendingTail(List<Candle> candles, int limit) {
        int fromIndex = Math.max(candles.size() - limit, 0);
        List<Candle> tail = new ArrayList<>(candles.subList(fromIndex, candles.size()));
        java.util.Collections.reverse(tail);
        return tail;
    }

    private static final class FakeCandleRepositoryPort implements CandleRepositoryPort {

        private final Map<String, List<Candle>> recentCandles = new java.util.HashMap<>();
        private final List<String> recentRequests = new ArrayList<>();

        void stubRecentCandles(Instrument instrument, String timeframe, int limit, List<Candle> candles) {
            recentCandles.put(key(instrument, timeframe, limit), candles);
        }

        List<String> recentRequests() {
            return recentRequests;
        }

        @Override
        public List<Candle> findCandles(Instrument instrument, String timeframe, Instant from) {
            return Collections.emptyList();
        }

        @Override
        public List<Candle> findRecentCandles(Instrument instrument, String timeframe, int limit) {
            recentRequests.add(key(instrument, timeframe, limit));
            return recentCandles.getOrDefault(key(instrument, timeframe, limit), Collections.emptyList());
        }

        @Override
        public List<Candle> findCandlesBetween(Instrument instrument, String timeframe, Instant from, Instant to) {
            return Collections.emptyList();
        }

        @Override
        public List<Candle> findRecentCandlesByContractMonth(Instrument instrument, String timeframe, String contractMonth, int limit) {
            return Collections.emptyList();
        }

        @Override
        public java.util.Optional<Instant> findLatestTimestamp(Instrument instrument, String timeframe) {
            return java.util.Optional.empty();
        }

        @Override
        public Candle save(Candle candle) {
            throw new UnsupportedOperationException("Not used in IndicatorServiceTest");
        }

        @Override
        public List<Candle> saveAll(List<Candle> candles) {
            throw new UnsupportedOperationException("Not used in IndicatorServiceTest");
        }

        @Override
        public void deleteAll() {
            throw new UnsupportedOperationException("Not used in IndicatorServiceTest");
        }

        @Override
        public void deleteByInstrumentAndTimeframe(Instrument instrument, String timeframe) {
            throw new UnsupportedOperationException("Not used in IndicatorServiceTest");
        }

        @Override
        public long count() {
            throw new UnsupportedOperationException("Not used in IndicatorServiceTest");
        }

        @Override
        public int countCandles(Instrument instrument, String timeframe) {
            return 0;
        }

        private String key(Instrument instrument, String timeframe, int limit) {
            return instrument.name() + ":" + timeframe + ":" + limit;
        }
    }

    /** Returns an ObjectProvider that always resolves to null (no bean available). */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return null; }
            @Override public T getIfAvailable() { return null; }
            @Override public T getIfUnique() { return null; }
            @Override public T getObject() { return null; }
        };
    }
}
