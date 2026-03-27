package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.ActiveContractCandleService;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
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

    private final ActiveContractCandleService activeContractCandleService;

    public CandleController(ActiveContractCandleService activeContractCandleService) {
        this.activeContractCandleService = activeContractCandleService;
    }

    @GetMapping("/{instrument}/{timeframe}")
    public ResponseEntity<List<Map<String, Object>>> getCandles(
            @PathVariable String instrument,
            @PathVariable String timeframe,
            @RequestParam(defaultValue = "300") @Min(1) @Max(1000) int limit) {

        Instrument inst;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        // Fetch most recent `limit` candles (desc), then reverse to oldest→newest
        List<Candle> candles = activeContractCandleService.findRecentCandles(inst, timeframe, limit);

        List<Candle> ordered = new java.util.ArrayList<>(candles);
        Collections.reverse(ordered);

        List<Map<String, Object>> result = ordered.stream().map(c -> Map.<String, Object>of(
            "time",  c.getTimestamp().getEpochSecond(),
            "open",  c.getOpen().doubleValue(),
            "high",  c.getHigh().doubleValue(),
            "low",   c.getLow().doubleValue(),
            "close", c.getClose().doubleValue(),
            "volume", c.getVolume()
        )).toList();

        return ResponseEntity.ok(result);
    }
}
