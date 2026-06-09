package com.riskdesk.presentation.controller;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.CandleSeriesNormalizer;
import com.riskdesk.presentation.controller.support.RequestInstants;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    @Value("${riskdesk.candles.range.default-page-size:5000}")
    private int rangeDefaultPageSize;

    @Value("${riskdesk.candles.range.max-page-size:50000}")
    private int rangeMaxPageSize;

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

    /**
     * Range read with cursor pagination — the backtest-fidelity counterpart to the limit-capped
     * chart endpoint above. Streams arbitrarily long 1m windows by paging on a timestamp cursor.
     *
     * <p>GET /api/candles/{instrument}/{timeframe}/range?from=ISO&to=ISO&limit=N
     *
     * <p>Returns RAW candles (no out-of-session purge, no contract-month filter) so the series
     * matches exactly what the backtest engine consumes. Candles are oldest→newest. When the page
     * is full, {@code nextFrom} carries the epoch-second cursor for the next page (pass it back as
     * {@code from}); it is {@code null} once the window is exhausted.</p>
     *
     * @param from inclusive ISO-8601 instant (e.g. {@code 2026-03-01T00:00:00Z}) or epoch seconds
     * @param to   inclusive ISO-8601 instant or epoch seconds
     * @param limit page size; defaults to {@code riskdesk.candles.range.default-page-size},
     *              clamped to {@code riskdesk.candles.range.max-page-size}
     */
    @GetMapping("/{instrument}/{timeframe}/range")
    public ResponseEntity<Map<String, Object>> getCandlesRange(
            @PathVariable String instrument,
            @PathVariable String timeframe,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) Integer limit) {

        Instrument inst;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + instrument));
        }

        Instant fromTs;
        Instant toTs;
        try {
            fromTs = RequestInstants.parse(from);
            toTs   = RequestInstants.parse(to);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid 'from'/'to'; use ISO-8601 (e.g. 2026-03-01T00:00:00Z) or epoch seconds."));
        }
        if (!fromTs.isBefore(toTs)) {
            return ResponseEntity.badRequest().body(Map.of("error", "'from' must be strictly before 'to'."));
        }

        int pageSize = (limit == null || limit <= 0) ? rangeDefaultPageSize : Math.min(limit, rangeMaxPageSize);

        List<Candle> page = candlePort.findCandlesBetweenPaged(inst, timeframe, fromTs, toTs, pageSize);

        List<Map<String, Object>> candles = page.stream().map(c -> Map.<String, Object>of(
            "time",   c.getTimestamp().getEpochSecond(),
            "open",   c.getOpen().doubleValue(),
            "high",   c.getHigh().doubleValue(),
            "low",    c.getLow().doubleValue(),
            "close",  c.getClose().doubleValue(),
            "volume", c.getVolume()
        )).toList();

        // Cursor: when the page is full there may be more — resume strictly after the last bar.
        // Never emit a cursor beyond `to` (the client would otherwise re-query an inverted window).
        Long nextFrom = null;
        if (page.size() == pageSize && !page.isEmpty()) {
            long candidate = page.get(page.size() - 1).getTimestamp().getEpochSecond() + 1;
            if (candidate <= toTs.getEpochSecond()) {
                nextFrom = candidate;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instrument", inst.name());
        body.put("timeframe", timeframe);
        body.put("from", fromTs.getEpochSecond());
        body.put("to", toTs.getEpochSecond());
        body.put("count", candles.size());
        body.put("nextFrom", nextFrom);
        body.put("candles", candles);
        return ResponseEntity.ok(body);
    }
}
