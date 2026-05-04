package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.strategy.WtxStrategyService;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignal;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wtx")
@ConditionalOnProperty(name = "riskdesk.wtx.enabled", havingValue = "true")
public class WtxStrategyController {

    private final WtxStrategyService wtxStrategyService;

    public WtxStrategyController(WtxStrategyService wtxStrategyService) {
        this.wtxStrategyService = wtxStrategyService;
    }

    @GetMapping("/state/{instrument}")
    public ResponseEntity<Map<String, Object>> getState(@PathVariable String instrument) {
        return wtxStrategyService.getState(instrument)
                .map(state -> ResponseEntity.ok(toStateView(state)))
                .orElseGet(() -> ResponseEntity.ok(defaultStateView(instrument)));
    }

    @GetMapping("/signals/recent")
    public ResponseEntity<List<Map<String, Object>>> getRecentSignals(
            @RequestParam String instrument,
            @RequestParam(defaultValue = "20") int limit
    ) {
        List<WtxSignal> signals = wtxStrategyService.getRecentSignals(instrument, limit);
        List<Map<String, Object>> views = signals.stream().map(this::toSignalView).toList();
        return ResponseEntity.ok(views);
    }

    private Map<String, Object> toStateView(WtxStrategyState state) {
        return Map.of(
                "instrument", state.instrument(),
                "currentDirection", state.currentPosition().name(),
                "dailyPnl", state.dailyPnl(),
                "dayStartEquity", state.dayStartEquity(),
                "currentEquity", state.currentEquity(),
                "maxDailyLossUsd", wtxStrategyService.getMaxDailyLossUsd(),
                "maxLossHit", state.maxLossHit(),
                "canTrade", !state.maxLossHit()
        );
    }

    private Map<String, Object> defaultStateView(String instrument) {
        return Map.of(
                "instrument", instrument,
                "currentDirection", "FLAT",
                "dailyPnl", 0,
                "dayStartEquity", wtxStrategyService.getInitialEquity(),
                "currentEquity", wtxStrategyService.getInitialEquity(),
                "maxDailyLossUsd", wtxStrategyService.getMaxDailyLossUsd(),
                "maxLossHit", false,
                "canTrade", true
        );
    }

    private Map<String, Object> toSignalView(WtxSignal signal) {
        return Map.of(
                "instrument", signal.instrument(),
                "timeframe", signal.timeframe(),
                "signalType", signal.signalType().name(),
                "direction", signal.direction(),
                "wt1Value", signal.wt1Value(),
                "wt2Value", signal.wt2Value(),
                "canTrade", signal.canTrade(),
                "actionTaken", signal.suggestedAction().name(),
                "enrichment", signal.enrichment(),
                "signalTs", signal.signalTs().toString()
        );
    }
}
