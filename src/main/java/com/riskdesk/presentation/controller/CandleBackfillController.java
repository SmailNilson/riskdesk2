package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.HistoricalDataService;
import com.riskdesk.application.service.HistoricalDataService.BackfillJob;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.presentation.controller.support.RequestInstants;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin endpoint to trigger a deep, idempotent range backfill of historical candles
 * (IBKR → PostgreSQL) so backtests have faithful 1m data over arbitrary windows.
 *
 * <pre>
 * POST /api/candles/backfill/{instrument}/{timeframe}?from=ISO&to=ISO[&async=true][&continuous=true][&replace=true]
 * GET  /api/candles/backfill/{instrument}/{timeframe}/status
 * </pre>
 *
 * <p>The backfill is idempotent: timestamps already present in the window are skipped, so the
 * endpoint is safe to re-run (e.g. daily) to top up missing bars. Heavy windows run async by
 * default — poll {@code /status} for completion.</p>
 *
 * <p>{@code continuous=true} sources the window from IBKR's continuous-contract series (CONTFUT):
 * at every past date the bars come from the contract that was actually front-month at that date,
 * instead of projecting today's front month into the past — required for windows that predate the
 * current contract's front period. {@code replace=true} purges the stored window first so it is
 * re-sourced rather than gap-filled (destructive; the idempotent skip would otherwise keep the
 * old rows).</p>
 */
@RestController
@RequestMapping("/api/candles/backfill")
@CrossOrigin
public class CandleBackfillController {

    private final HistoricalDataService historicalDataService;

    public CandleBackfillController(HistoricalDataService historicalDataService) {
        this.historicalDataService = historicalDataService;
    }

    @PostMapping("/{instrument}/{timeframe}")
    public ResponseEntity<Map<String, Object>> backfill(
            @PathVariable String instrument,
            @PathVariable String timeframe,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "true") boolean async,
            @RequestParam(defaultValue = "false") boolean continuous,
            @RequestParam(defaultValue = "false") boolean replace) {

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

        BackfillJob job = historicalDataService.startBackfillRange(inst, timeframe, fromTs, toTs, async,
            continuous, replace);
        return ResponseEntity.status(statusFor(job)).body(toBody(job));
    }

    @GetMapping("/{instrument}/{timeframe}/status")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable String instrument,
            @PathVariable String timeframe) {

        Instrument inst;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + instrument));
        }

        return historicalDataService.backfillStatus(inst, timeframe)
            .map(job -> ResponseEntity.ok(toBody(job)))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "No backfill job found for " + inst + " " + timeframe)));
    }

    private static HttpStatus statusFor(BackfillJob job) {
        return switch (job.state()) {
            case "RUNNING"  -> HttpStatus.ACCEPTED;
            case "DONE"     -> HttpStatus.OK;
            case "PARTIAL"  -> HttpStatus.PARTIAL_CONTENT; // replace purged but refill fell short — re-run
            case "REJECTED" -> HttpStatus.BAD_REQUEST;
            case "DISABLED" -> HttpStatus.CONFLICT;
            default          -> HttpStatus.INTERNAL_SERVER_ERROR; // FAILED
        };
    }

    private static Map<String, Object> toBody(BackfillJob job) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instrument", job.instrument().name());
        body.put("timeframe", job.timeframe());
        body.put("state", job.state());
        body.put("from", job.from() != null ? job.from().getEpochSecond() : null);
        body.put("to", job.to() != null ? job.to().getEpochSecond() : null);
        body.put("fetched", job.fetched());
        body.put("existing", job.existing());
        body.put("saved", job.saved());
        body.put("startedAt", job.startedAt());
        body.put("finishedAt", job.finishedAt());
        body.put("message", job.message());
        body.put("continuous", job.continuous());
        body.put("replace", job.replace());
        return body;
    }
}
