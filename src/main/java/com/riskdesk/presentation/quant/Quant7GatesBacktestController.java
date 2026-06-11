package com.riskdesk.presentation.quant;

import com.riskdesk.application.quant.backtest.Quant7GatesExitBacktestService;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.backtest.QuantExitReplayParams;
import com.riskdesk.domain.quant.backtest.QuantExitReplayResult;
import com.riskdesk.domain.quant.simulation.QuantSimExitPolicy;
import com.riskdesk.domain.quant.simulation.QuantSimStopMode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exit-policy replay over the recorded Quant 7-Gates signals.
 *
 * <p>{@code GET /api/quant/backtest/exits} re-manages every persisted
 * simulation entry under a parameterised exit bundle (ATR or fixed stops,
 * flow-AVOID policy, HTF EMA filter, commission) against historical 1m
 * candles — pessimistic both-cross rule, no lookahead, EOD flat. Use it to
 * compare policy variants on the SAME order-flow entry signals, e.g.:
 *
 * <pre>
 *   /api/quant/backtest/exits                              → calibrated defaults
 *   /api/quant/backtest/exits?exitPolicy=FLOW_AVOID&amp;stopMode=FIXED&amp;htfFilter=false
 *                                                          → legacy behaviour
 *   /api/quant/backtest/exits?slAtrMult=1.5&amp;tpAtrMult=2.5  → tighter ATR variant
 * </pre>
 */
@RestController
@RequestMapping("/api/quant/backtest")
public class Quant7GatesBacktestController {

    private final Quant7GatesExitBacktestService service;

    public Quant7GatesBacktestController(Quant7GatesExitBacktestService service) {
        this.service = service;
    }

    @GetMapping("/exits")
    public ResponseEntity<Map<String, Object>> exits(
            @RequestParam(required = false) String instrument,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "ATR") String stopMode,
            @RequestParam(defaultValue = "25") double fixedSlPts,
            @RequestParam(defaultValue = "40") double fixedTpPts,
            @RequestParam(defaultValue = "14") int atrPeriod,
            @RequestParam(defaultValue = "2.0") double slAtrMult,
            @RequestParam(defaultValue = "3.0") double tpAtrMult,
            @RequestParam(defaultValue = "SLTP_ONLY") String exitPolicy,
            @RequestParam(defaultValue = "true") boolean htfFilter,
            @RequestParam(defaultValue = "20") int htfEmaFast,
            @RequestParam(defaultValue = "50") int htfEmaSlow,
            @RequestParam(defaultValue = "1.24") double commissionUsd,
            @RequestParam(defaultValue = "false") boolean includeTrades) {

        Instrument instrumentFilter = null;
        if (instrument != null && !instrument.isBlank() && !"ALL".equalsIgnoreCase(instrument)) {
            try {
                instrumentFilter = Instrument.valueOf(instrument.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + instrument));
            }
        }

        Instant fromTs;
        Instant toTs;
        try {
            fromTs = from == null || from.isBlank() ? null : Instant.parse(from);
            toTs = to == null || to.isBlank() ? null : Instant.parse(to);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid 'from'/'to'; use ISO-8601 (e.g. 2026-06-01T00:00:00Z)."));
        }

        QuantSimStopMode mode;
        QuantSimExitPolicy policy;
        try {
            mode = QuantSimStopMode.valueOf(stopMode.toUpperCase());
            policy = QuantSimExitPolicy.valueOf(exitPolicy.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "stopMode must be FIXED|ATR, exitPolicy must be "
                    + "SLTP_ONLY|FLOW_AVOID_IN_PROFIT|FLOW_AVOID."));
        }

        QuantExitReplayParams params = new QuantExitReplayParams(
            mode, fixedSlPts, fixedTpPts,
            atrPeriod, slAtrMult, tpAtrMult,
            policy, htfFilter, htfEmaFast, htfEmaSlow,
            commissionUsd, LocalTime.of(16, 55));

        QuantExitReplayResult result = service.run(instrumentFilter, fromTs, toTs, params);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        body.put("overall", result.overall());
        body.put("byInstrument", result.byInstrument());
        body.put("byDirection", result.byDirection());
        body.put("byExitReason", result.byExitReason());
        body.put("byDay", result.byDay());
        body.put("skippedHtf", result.skippedHtf());
        body.put("skippedNoData", result.skippedNoData());
        if (includeTrades) {
            body.put("trades", result.trades());
        }
        return ResponseEntity.ok(body);
    }
}
