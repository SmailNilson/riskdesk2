package com.riskdesk.application.service;

import com.riskdesk.application.dto.MentorIntermarketSnapshot;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Intermarket values must come from the internal IBKR -> PostgreSQL pipeline only.
 */
@Service
public class MentorIntermarketService {

    private final Optional<MarketDataService> marketDataService;
    private final CandleRepositoryPort candleRepositoryPort;

    public MentorIntermarketService(Optional<MarketDataService> marketDataService,
                                    CandleRepositoryPort candleRepositoryPort) {
        this.marketDataService = marketDataService;
        this.candleRepositoryPort = candleRepositoryPort;
    }

    public MentorIntermarketSnapshot current(Instrument focusInstrument) {
        if (focusInstrument != null && !focusInstrument.isDollarSensitive()) {
            return new MentorIntermarketSnapshot(
                null,
                "NOT_REQUIRED",
                null,
                null,
                null,
                "UNAVAILABLE"
            );
        }

        DxySignal dxySignal = loadDxySignal();
        return new MentorIntermarketSnapshot(
            dxySignal.pctChange(),
            dxySignal.trend(),
            null,
            null,
            null,
            dxySignal.available() ? "DXY_AVAILABLE" : "UNAVAILABLE"
        );
    }

    private DxySignal loadDxySignal() {
        List<Candle> recentCandles = recentDxyCandles();
        if (recentCandles.isEmpty()) {
            return DxySignal.unavailable();
        }

        BigDecimal latestKnownClose = recentCandles.get(0).getClose();
        BigDecimal baseline = recentCandles.size() > 1 ? recentCandles.get(1).getClose() : latestKnownClose;
        if (baseline == null || baseline.compareTo(BigDecimal.ZERO) == 0) {
            return DxySignal.unavailable();
        }

        MarketDataService.StoredPrice liveOrCached = marketDataService
            .map(service -> service.currentPrice(Instrument.DXY))
            .orElse(null);
        BigDecimal current = liveOrCached != null && liveOrCached.price() != null
            ? liveOrCached.price()
            : latestKnownClose;
        if (current == null) {
            return DxySignal.unavailable();
        }

        BigDecimal pct = current.subtract(baseline)
            .divide(baseline, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
            .setScale(3, RoundingMode.HALF_UP);

        return new DxySignal(
            pct.doubleValue(),
            trendFor(current.compareTo(baseline)),
            true
        );
    }

    private List<Candle> recentDxyCandles() {
        List<Candle> recent10m = candleRepositoryPort.findRecentCandles(Instrument.DXY, "10m", 2);
        if (!recent10m.isEmpty()) {
            return recent10m;
        }
        return candleRepositoryPort.findRecentCandles(Instrument.DXY, "1h", 2);
    }

    private String trendFor(int comparison) {
        if (comparison > 0) {
            return "BULLISH";
        }
        if (comparison < 0) {
            return "BEARISH";
        }
        return "FLAT";
    }

    private record DxySignal(Double pctChange, String trend, boolean available) {
        private static DxySignal unavailable() {
            return new DxySignal(null, "UNAVAILABLE", false);
        }
    }
}
