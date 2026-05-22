package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.strategy.PlaybookStrategyService;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookProfile;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookSignal;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookStrategyState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/playbook-strategy")
@ConditionalOnProperty(name = "riskdesk.playbook.enabled", havingValue = "true")
public class PlaybookStrategyController {

    private final PlaybookStrategyService playbookStrategyService;

    public PlaybookStrategyController(PlaybookStrategyService playbookStrategyService) {
        this.playbookStrategyService = playbookStrategyService;
    }

    @GetMapping("/state/{instrument}/{timeframe}")
    public ResponseEntity<Map<String, Object>> getState(@PathVariable String instrument,
                                                        @PathVariable String timeframe) {
        return playbookStrategyService.getState(instrument, timeframe)
                .map(state -> ResponseEntity.ok(toStateView(state)))
                .orElseGet(() -> ResponseEntity.ok(defaultStateView(instrument, timeframe)));
    }

    @GetMapping("/signals/recent")
    public ResponseEntity<List<Map<String, Object>>> getRecentSignals(
            @RequestParam String instrument,
            @RequestParam String timeframe,
            @RequestParam(defaultValue = "20") int limit
    ) {
        List<PlaybookSignal> signals = playbookStrategyService.getRecentSignals(instrument, timeframe, limit);
        List<Map<String, Object>> views = signals.stream().map(this::toSignalView).toList();
        return ResponseEntity.ok(views);
    }

    @PutMapping("/state/{instrument}/{timeframe}/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @PathVariable String instrument,
            @PathVariable String timeframe,
            @RequestBody Map<String, String> body
    ) {
        String raw = body == null ? null : body.get("profile");
        if (raw == null || raw.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'profile' field"));
        }
        PlaybookProfile profile;
        try {
            profile = PlaybookProfile.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown profile: " + raw,
                    "allowed", List.of("BASELINE", "SESSION_ATR", "STRICT")
            ));
        }
        PlaybookStrategyState updated = playbookStrategyService.updateProfile(instrument, timeframe, profile);
        return ResponseEntity.ok(toStateView(updated));
    }

    @PutMapping("/state/{instrument}/{timeframe}/auto-execution")
    public ResponseEntity<Map<String, Object>> updateAutoExecution(
            @PathVariable String instrument,
            @PathVariable String timeframe,
            @RequestBody Map<String, Object> body
    ) {
        if (body == null || !body.containsKey("enabled")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'enabled' boolean field"));
        }
        boolean enabled;
        Object raw = body.get("enabled");
        if (raw instanceof Boolean b) {
            enabled = b;
        } else if (raw instanceof String s) {
            enabled = Boolean.parseBoolean(s);
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "'enabled' must be boolean"));
        }
        PlaybookStrategyState updated = playbookStrategyService.updateAutoExecution(instrument, timeframe, enabled);
        return ResponseEntity.ok(toStateView(updated));
    }

    @PutMapping("/state/{instrument}/{timeframe}/order-qty")
    public ResponseEntity<Map<String, Object>> updateOrderQty(
            @PathVariable String instrument,
            @PathVariable String timeframe,
            @RequestBody Map<String, Object> body
    ) {
        if (body == null || !body.containsKey("qty")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'qty' field"));
        }
        int qty;
        Object raw = body.get("qty");
        try {
            if (raw instanceof Number n) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.floor(d)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "'qty' must be a whole number, got: " + raw));
                }
                if (d > Integer.MAX_VALUE || d < Integer.MIN_VALUE) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "'qty' out of int range: " + raw));
                }
                qty = (int) d;
            } else if (raw instanceof String s) {
                qty = Integer.parseInt(s.trim());
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "'qty' must be an integer"));
            }
        } catch (NumberFormatException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "'qty' must be an integer: " + raw));
        }
        if (qty <= 0 || qty > 100) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "'qty' must be between 1 and 100 contracts",
                    "received", qty
            ));
        }
        PlaybookStrategyState updated = playbookStrategyService.updateConfiguredOrderQty(instrument, timeframe, qty);
        return ResponseEntity.ok(toStateView(updated));
    }

    private Map<String, Object> toStateView(PlaybookStrategyState state) {
        Map<String, Object> view = new HashMap<>();
        view.put("instrument", state.instrument());
        view.put("timeframe", state.timeframe());
        view.put("currentDirection", state.currentPosition().name());
        view.put("dailyPnl", state.dailyPnl());
        view.put("dayStartEquity", state.dayStartEquity());
        view.put("currentEquity", state.currentEquity());
        view.put("maxDailyLossUsd", playbookStrategyService.getMaxDailyLossUsd());
        view.put("maxLossHit", state.maxLossHit());
        PlaybookProfile profile = state.activeProfile() != null ? state.activeProfile() : PlaybookProfile.BASELINE;
        view.put("activeProfile", profile.name());
        view.put("autoExecutionEnabled", state.autoExecutionEnabled());
        view.put("configuredOrderQty", state.configuredOrderQty());
        view.put("canTrade", !state.maxLossHit() || !profile.blocksOnMaxLoss());
        return view;
    }

    private Map<String, Object> defaultStateView(String instrument, String timeframe) {
        Map<String, Object> view = new HashMap<>();
        view.put("instrument", instrument);
        view.put("timeframe", timeframe);
        view.put("currentDirection", "FLAT");
        view.put("dailyPnl", 0);
        view.put("dayStartEquity", playbookStrategyService.getInitialEquity());
        view.put("currentEquity", playbookStrategyService.getInitialEquity());
        view.put("maxDailyLossUsd", playbookStrategyService.getMaxDailyLossUsd());
        view.put("maxLossHit", false);
        view.put("activeProfile", PlaybookProfile.BASELINE.name());
        view.put("autoExecutionEnabled", false);
        view.put("configuredOrderQty", 1);
        view.put("canTrade", true);
        return view;
    }

    private Map<String, Object> toSignalView(PlaybookSignal signal) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", signal.id().toString());
        view.put("instrument", signal.instrument());
        view.put("timeframe", signal.timeframe());
        view.put("direction", signal.direction());
        view.put("checklistScore", signal.checklistScore());
        view.put("setupType", signal.setupType());
        view.put("entryPrice", signal.entryPrice());
        view.put("stopLoss", signal.stopLoss());
        view.put("takeProfit1", signal.takeProfit1());
        view.put("takeProfit2", signal.takeProfit2());
        view.put("evaluatedAt", signal.evaluatedAt().toString());
        view.put("routingOutcome", signal.routingOutcome() != null ? signal.routingOutcome().name() : null);
        view.put("routingErrorMessage", signal.routingErrorMessage());
        return view;
    }
}
