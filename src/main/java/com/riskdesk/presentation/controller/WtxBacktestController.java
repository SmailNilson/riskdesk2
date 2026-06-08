package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.strategy.WtxBacktestRequest;
import com.riskdesk.application.service.strategy.WtxBacktestService;
import com.riskdesk.domain.engine.backtest.BacktestResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Faithful WTX replay backtest endpoint — reuses the live evaluators (signals + HTF gate + SL/trailing/TP
 * exits) over real 1m candles, so a take-profit A/B reflects the live HTF / SL_ONLY strategy. Distinct from
 * {@code /api/backtest} (the divergent {@code WaveTrendBacktest}, which does not model the HTF filter).
 *
 * <p>A/B the take-profit by posting the same window twice with {@code takeProfitEnabled} false vs true (and
 * sweeping {@code tpPoints}); compare {@code totalPnl} / {@code profitFactor} / {@code maxDrawdown}.</p>
 */
@RestController
@RequestMapping("/api/wtx")
public class WtxBacktestController {

    private final WtxBacktestService service;

    public WtxBacktestController(WtxBacktestService service) {
        this.service = service;
    }

    @PostMapping("/backtest")
    public ResponseEntity<BacktestResult> backtest(@RequestBody WtxBacktestRequest request) {
        return ResponseEntity.ok(service.run(request));
    }
}
