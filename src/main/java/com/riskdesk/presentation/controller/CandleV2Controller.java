package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.OnDemandCandleService;
import com.riskdesk.application.service.OnDemandCandleService.OnDemandCandleResponse;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.CandleSeriesNormalizer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V2 candle endpoint supporting on-demand loading with scroll-back (TradingView UDF style).
 *
 * <pre>
 * GET /api/v2/candles/{instrument}/{timeframe}?to=epoch&amp;countBack=300
 * GET /api/v2/candles/{instrument}/{timeframe}?from=epoch&amp;to=epoch
 * </pre>
 *
 * Returns: {@code {"bars": [...], "noData": false, "nextTime": null}}
 */
@RestController
@RequestMapping("/api/v2/candles")
@CrossOrigin
public class CandleV2Controller {

    private final OnDemandCandleService onDemandService;

    public CandleV2Controller(OnDemandCandleService onDemandService) {
        this.onDemandService = onDemandService;
    }

    @GetMapping("/{instrument}/{timeframe}")
    public ResponseEntity<Map<String, Object>> getCandles(
            @PathVariable String instrument,
            @PathVariable String timeframe,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) Long from,
            @RequestParam(defaultValue = "300") int countBack) {

        Instrument inst;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        // If from/to range is specified, compute countBack from the range
        if (from != null && to != null) {
            long tfSeconds = timeframeSeconds(timeframe);
            countBack = Math.max(1, (int) ((to - from) / tfSeconds) + 10);
        }

        countBack = Math.min(countBack, 2000);

        OnDemandCandleResponse response = onDemandService.getBars(inst, timeframe, to, countBack);

        // Apply session filter
        List<Candle> filtered = CandleSeriesNormalizer.purgeOutOfSession(response.bars(), inst);

        List<Map<String, Object>> bars = filtered.stream().map(c -> {
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("time", c.getTimestamp().getEpochSecond());
            bar.put("open", c.getOpen().doubleValue());
            bar.put("high", c.getHigh().doubleValue());
            bar.put("low", c.getLow().doubleValue());
            bar.put("close", c.getClose().doubleValue());
            bar.put("volume", c.getVolume());
            return bar;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bars", bars);
        result.put("noData", filtered.isEmpty() && response.noData());
        result.put("nextTime", response.nextTime());

        return ResponseEntity.ok(result);
    }

    private static long timeframeSeconds(String timeframe) {
        return switch (timeframe) {
            case "5m"  -> 300L;
            case "10m" -> 600L;
            case "30m" -> 1800L;
            case "1h"  -> 3600L;
            case "4h"  -> 14400L;
            case "1d"  -> 86400L;
            default    -> 600L;
        };
    }
}
