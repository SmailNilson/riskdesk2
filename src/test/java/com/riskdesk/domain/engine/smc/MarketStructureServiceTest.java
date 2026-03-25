package com.riskdesk.domain.engine.smc;

import com.riskdesk.application.service.MarketStructureService;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketStructureServiceTest {

    private final MarketStructureService service = new MarketStructureService();

    @Test
    void confirmedSwing_isNotAvailableBeforeRightSideBarsClose() {
        List<Candle> candles = List.of(
            candle("2026-01-01T00:00:00Z", 100, 101, 99, 100),
            candle("2026-01-01T01:00:00Z", 101, 102, 100, 101),
            candle("2026-01-01T02:00:00Z", 102, 110, 101, 103),
            candle("2026-01-01T03:00:00Z", 101, 102, 100, 101),
            candle("2026-01-01T04:00:00Z", 100, 101, 99, 100)
        );

        List<MarketStructureService.ConfirmedSwing> swings = service.detectConfirmedSwings(candles, 2, "1h");

        assertEquals(1, swings.size());
        assertEquals(MarketStructure.SwingType.HIGH, swings.get(0).type());
        assertEquals(Instant.parse("2026-01-01T02:00:00Z"), swings.get(0).pivotTime());
        assertEquals(Instant.parse("2026-01-01T04:00:00Z"), swings.get(0).confirmedAt());
    }

    @Test
    void structureContextIndex_detectsBullishBreakWithoutLookahead() {
        List<Candle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        double[] closes = {100, 102, 105, 102, 100, 103, 106, 111, 112};
        for (int i = 0; i < closes.length; i++) {
            double close = closes[i];
            candles.add(new Candle(
                Instrument.MNQ,
                "1h",
                start.plus(i, ChronoUnit.HOURS),
                BigDecimal.valueOf(close - 0.5),
                BigDecimal.valueOf(close + 0.5),
                BigDecimal.valueOf(close - 1.0),
                BigDecimal.valueOf(close),
                1000
            ));
        }

        MarketStructureService.StructureContextIndex index = service.buildStructureContextIndex(candles, 2, "1h");
        MarketStructureService.StructureContext context = index.contextAt(Instant.parse("2026-01-01T06:00:00Z"));

        assertEquals(MarketStructure.Trend.BULLISH, context.trend());
        assertNotNull(context.lastEvent());
        assertEquals(MarketStructure.StructureType.CHOCH, context.lastEvent().type());
        assertEquals(Instant.parse("2026-01-01T06:00:00Z"), context.lastEvent().breakTime());
    }

    private static Candle candle(String ts, double open, double high, double low, double close) {
        return new Candle(
            Instrument.MNQ,
            "1h",
            Instant.parse(ts),
            BigDecimal.valueOf(open),
            BigDecimal.valueOf(high),
            BigDecimal.valueOf(low),
            BigDecimal.valueOf(close),
            1000
        );
    }
}
