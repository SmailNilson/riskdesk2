package com.riskdesk.presentation.controller;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.CandleSeriesNormalizer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Serves historical OHLCV candles for the chart.
 *
 * GET /api/candles/{instrument}/{timeframe}?limit=300
 *
 * Returns candles ordered oldest→newest (as lightweight-charts requires).
 * Always filters by the active contract month so the chart never mixes contracts.
 */
@Validated
@RestController
@RequestMapping("/api/candles")
@CrossOrigin
public class CandleController {

    private final CandleRepositoryPort   candlePort;
    private final ActiveContractRegistry contractRegistry;

    public CandleController(CandleRepositoryPort candlePort, ActiveContractRegistry contractRegistry) {
        this.candlePort       = candlePort;
        this.contractRegistry = contractRegistry;
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

        String contractMonth = contractRegistry.getContractMonth(inst).orElse(null);
        List<Candle> candles;
        if (contractMonth != null) {
            candles = candlePort.findRecentCandlesByContractMonth(inst, timeframe, contractMonth, limit);
            if (candles.isEmpty()) {
                candles = candlePort.findRecentCandles(inst, timeframe, limit);
                candles = candles.stream()
                    .filter(c -> c.getContractMonth() == null || contractMonth.equals(c.getContractMonth()))
                    .toList();
            }
        } else {
            candles = candlePort.findRecentCandles(inst, timeframe, limit);
        }

        List<Candle> ordered = new ArrayList<>(candles);
        Collections.reverse(ordered);
        ordered = CandleSeriesNormalizer.purgeOutOfSession(ordered, inst);

        List<Map<String, Object>> result = ordered.stream().map(c -> Map.<String, Object>of(
            "time",   c.getTimestamp().getEpochSecond(),
            "open",   c.getOpen().doubleValue(),
            "high",   c.getHigh().doubleValue(),
            "low",    c.getLow().doubleValue(),
            "close",  c.getClose().doubleValue(),
            "volume", c.getVolume()
        )).toList();

        return ResponseEntity.ok(result);
    }
}
