package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSeriesSnapshot;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class IndicatorServiceTest {

    @Test
    void computeSeriesLoadsWarmupHistoryButReturnsRequestedWindow() {
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);
        when(contractRegistry.getContractMonth(Instrument.MCL)).thenReturn(Optional.empty());

        List<Candle> history = buildHistory(1_600);
        when(candlePort.findRecentCandles(Instrument.MCL, "10m", 1_500))
                .thenReturn(descendingTail(history, 1_500));

        IndicatorService service = new IndicatorService(candlePort, contractRegistry);

        IndicatorSeriesSnapshot series = service.computeSeries(Instrument.MCL, "10m", 500);

        verify(candlePort).findRecentCandles(Instrument.MCL, "10m", 1_500);
        assertEquals(500, series.ema9().size());
        assertEquals(500, series.ema50().size());
        assertEquals(500, series.ema200().size());
        assertEquals(500, series.bollingerBands().size());
        assertEquals(500, series.waveTrend().size());
        assertEquals(history.get(history.size() - 1).getTimestamp().getEpochSecond(), series.ema9().get(series.ema9().size() - 1).time());
    }

    private static List<Candle> buildHistory(int count) {
        List<Candle> candles = new ArrayList<>(count);
        Instant start = Instant.parse("2026-03-01T00:00:00Z");
        for (int i = 0; i < count; i++) {
            BigDecimal price = BigDecimal.valueOf(90 + (i * 0.05));
            candles.add(new Candle(
                    Instrument.MCL,
                    "10m",
                    start.plusSeconds(i * 600L),
                    price,
                    price.add(BigDecimal.valueOf(0.2)),
                    price.subtract(BigDecimal.valueOf(0.2)),
                    price.add(BigDecimal.valueOf(0.1)),
                    1_000L + i
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
}
