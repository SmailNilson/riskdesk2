package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.strategy.wtxrsi.WtxRsiStrategyService;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignalRecord;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Live operational endpoints for the WTX+RSI strategy.
 *
 * <p>Bound only when {@code riskdesk.wtxrsi.enabled=true} — same gate as
 * {@link com.riskdesk.application.service.strategy.wtxrsi.WtxRsiStrategyService}.
 *
 * <p>Endpoints (all under {@code /api/strategy/wtxrsi}):
 * <ul>
 *   <li>{@code GET /state/{instrument}/{timeframe}} — current persisted state</li>
 *   <li>{@code POST /state/{instrument}/{timeframe}/auto-execution} — flip the IBKR toggle</li>
 *   <li>{@code POST /state/{instrument}/{timeframe}/order-qty}     — set panel quantity</li>
 *   <li>{@code GET /signals/{instrument}}                          — recent signal log</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/strategy/wtxrsi")
@ConditionalOnProperty(name = "riskdesk.wtxrsi.enabled", havingValue = "true")
public class WtxRsiStrategyController {

    private final WtxRsiStrategyService service;

    public WtxRsiStrategyController(WtxRsiStrategyService service) {
        this.service = service;
    }

    @GetMapping("/state/{instrument}/{timeframe}")
    public ResponseEntity<WtxRsiStrategyState> getState(
            @PathVariable String instrument, @PathVariable String timeframe) {
        Optional<WtxRsiStrategyState> state = service.getState(instrument, timeframe);
        return state.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(WtxRsiStrategyState.initial(instrument, timeframe)));
    }

    @PostMapping("/state/{instrument}/{timeframe}/auto-execution")
    public ResponseEntity<WtxRsiStrategyState> toggleAutoExecution(
            @PathVariable String instrument, @PathVariable String timeframe,
            @RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return ResponseEntity.ok(service.toggleAutoExecution(instrument, timeframe, enabled));
    }

    @PostMapping("/state/{instrument}/{timeframe}/order-qty")
    public ResponseEntity<WtxRsiStrategyState> setOrderQty(
            @PathVariable String instrument, @PathVariable String timeframe,
            @RequestBody Map<String, Integer> body) {
        int qty = body.getOrDefault("quantity", 1);
        return ResponseEntity.ok(service.setOrderQty(instrument, timeframe, qty));
    }

    @PostMapping("/state/{instrument}/{timeframe}/swing-bias-filter")
    public ResponseEntity<WtxRsiStrategyState> toggleSwingBiasFilter(
            @PathVariable String instrument, @PathVariable String timeframe,
            @RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return ResponseEntity.ok(service.toggleSwingBiasFilter(instrument, timeframe, enabled));
    }

    @GetMapping("/signals/{instrument}")
    public ResponseEntity<List<WtxRsiSignalRecord>> recentSignals(
            @PathVariable String instrument,
            @RequestParam(required = false) String timeframe,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(service.recentSignals(instrument, timeframe, limit));
    }
}
