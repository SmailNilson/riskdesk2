package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.strategy.wtxrsi.WtxRsiBacktestRequest;
import com.riskdesk.application.service.strategy.wtxrsi.WtxRsiBacktestResponse;
import com.riskdesk.application.service.strategy.wtxrsi.WtxRsiBacktestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand backtest endpoint for the WTX+RSI strategy.
 *
 * <pre>
 * POST /api/strategy/wtxrsi/backtest
 * {
 *   "instrument": "MNQ",
 *   "timeframe": "5m",
 *   "from": "2025-01-01T00:00:00Z",
 *   "to":   "2025-04-30T23:59:59Z",
 *   "syncLookbackBars": 3,                   // optional
 *   "zoneMode": "STRICT_ZONE",               // STRICT_ZONE | VISITED_RECENTLY | CROSS_FROM_ZONE
 *   "tpMode": "R_MULTIPLE",                  // REVERSAL | R_MULTIPLE
 *   "tpRMultiple": 2.0                       // ignored unless tpMode = R_MULTIPLE
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/strategy/wtxrsi")
public class WtxRsiBacktestController {

    private static final Logger log = LoggerFactory.getLogger(WtxRsiBacktestController.class);

    private final WtxRsiBacktestService service;

    public WtxRsiBacktestController(WtxRsiBacktestService service) {
        this.service = service;
    }

    @PostMapping("/backtest")
    public ResponseEntity<WtxRsiBacktestResponse> backtest(@RequestBody WtxRsiBacktestRequest request) {
        log.info("WTX-RSI backtest requested: {} {} {} → {}",
                request.instrument(), request.timeframe(), request.from(), request.to());
        WtxRsiBacktestResponse response = service.run(request);
        return ResponseEntity.ok(response);
    }
}
