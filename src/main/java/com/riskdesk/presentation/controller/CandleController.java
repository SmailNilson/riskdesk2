package com.riskdesk.presentation.controller;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.WatchlistCandle;
import com.riskdesk.application.service.WatchlistDashboardService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Serves historical OHLCV candles for the chart.
 *
 * GET /api/candles/{instrument}/{timeframe}?limit=300
 *
 * Returns candles ordered oldest→newest (as lightweight-charts requires).
 */
@Validated
@RestController
@RequestMapping("/api/candles")
@org.springframework.web.bind.annotation.CrossOrigin
public class CandleController {

    private final CandleRepositoryPort candlePort;
    private final WatchlistDashboardService watchlistDashboardService;

    public CandleController(CandleRepositoryPort candlePort,
                            WatchlistDashboardService watchlistDashboardService) {
        this.candlePort = candlePort;
        this.watchlistDashboardService = watchlistDashboardService;
    }

    @GetMapping("/{instrument}/{timeframe}")
    public ResponseEntity<List<Map<String, Object>>> getCandles(
            @PathVariable String instrument,
            @PathVariable String timeframe,
            @RequestParam(defaultValue = "300") @Min(1) @Max(1000) int limit) {

        try {
            Instrument inst = Instrument.valueOf(instrument.toUpperCase());
            List<Candle> candles = candlePort.findRecentCandles(inst, timeframe, limit);
            List<Candle> ordered = new java.util.ArrayList<>(candles);
            Collections.reverse(ordered);
            return ResponseEntity.ok(ordered.stream().map(this::toMap).toList());
        } catch (IllegalArgumentException ignored) {
            List<WatchlistCandle> candles = watchlistDashboardService.recentCandles(instrument, timeframe, limit);
            return ResponseEntity.ok(candles.stream().map(this::toMap).toList());
        }
    }

    private Map<String, Object> toMap(Candle candle) {
        return Map.of(
            "time", candle.getTimestamp().getEpochSecond(),
            "open", candle.getOpen().doubleValue(),
            "high", candle.getHigh().doubleValue(),
            "low", candle.getLow().doubleValue(),
            "close", candle.getClose().doubleValue(),
            "volume", candle.getVolume()
        );
    }

    private Map<String, Object> toMap(WatchlistCandle candle) {
        return Map.of(
            "time", candle.getTimestamp().getEpochSecond(),
            "open", candle.getOpen().doubleValue(),
            "high", candle.getHigh().doubleValue(),
            "low", candle.getLow().doubleValue(),
            "close", candle.getClose().doubleValue(),
            "volume", candle.getVolume()
        );
    }
}
