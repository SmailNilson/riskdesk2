package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.strategy.WtxStrategyService;
import com.riskdesk.domain.engine.strategy.wtx.WtxProfile;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignal;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
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

    @GetMapping("/state/{instrument}/{timeframe}")
    public ResponseEntity<Map<String, Object>> getState(@PathVariable String instrument,
                                                        @PathVariable String timeframe) {
        return wtxStrategyService.getState(instrument, timeframe)
                .map(state -> ResponseEntity.ok(toStateView(state)))
                .orElseGet(() -> ResponseEntity.ok(defaultStateView(instrument, timeframe)));
    }

    @GetMapping("/signals/recent")
    public ResponseEntity<List<Map<String, Object>>> getRecentSignals(
            @RequestParam String instrument,
            @RequestParam(required = false) String timeframe,
            @RequestParam(defaultValue = "20") int limit
    ) {
        List<WtxSignal> signals = timeframe != null
                ? wtxStrategyService.getRecentSignals(instrument, timeframe, limit)
                : wtxStrategyService.getRecentSignals(instrument, limit);
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
        WtxProfile profile;
        try {
            profile = WtxProfile.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown profile: " + raw,
                    "allowed", List.of("BASELINE", "SESSION_ATR", "HTF", "STRICT")
            ));
        }
        WtxStrategyState updated = wtxStrategyService.updateProfile(instrument, timeframe, profile);
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
        WtxStrategyState updated = wtxStrategyService.updateAutoExecution(instrument, timeframe, enabled);
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
                // Reject fractional numerics — Number.intValue() silently truncates 1.9 → 1,
                // which would size a trade differently than the client asked for.
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
                // parseInt already rejects "1.9" with NumberFormatException — caught below.
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
        WtxStrategyState updated = wtxStrategyService.updateConfiguredOrderQty(instrument, timeframe, qty);
        return ResponseEntity.ok(toStateView(updated));
    }

    @PutMapping("/state/{instrument}/{timeframe}/swing-bias-filter")
    public ResponseEntity<Map<String, Object>> updateSwingBiasFilter(
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
            // Strict parse: only "true" / "false" (case-insensitive). `Boolean.parseBoolean`
            // silently coerces "tru", "yes", "1" etc. to false, which would disable the
            // filter from malformed client input while still returning 200.
            String normalized = s.trim().toLowerCase();
            if ("true".equals(normalized)) {
                enabled = true;
            } else if ("false".equals(normalized)) {
                enabled = false;
            } else {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "'enabled' must be boolean (true/false), got: " + s));
            }
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "'enabled' must be boolean"));
        }
        WtxStrategyState updated = wtxStrategyService.updateSwingBiasFilter(instrument, timeframe, enabled);
        return ResponseEntity.ok(toStateView(updated));
    }

    @PutMapping("/state/{instrument}/{timeframe}/telegram-notifications")
    public ResponseEntity<Map<String, Object>> updateTelegramNotifications(
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
            // Strict parse: only "true" / "false" (case-insensitive). `Boolean.parseBoolean`
            // silently coerces "tru", "yes", "1" etc. to false, which would mute the panel
            // from malformed client input while still returning 200. Mirrors the swing-bias
            // filter endpoint.
            String normalized = s.trim().toLowerCase();
            if ("true".equals(normalized)) {
                enabled = true;
            } else if ("false".equals(normalized)) {
                enabled = false;
            } else {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "'enabled' must be boolean (true/false), got: " + s));
            }
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "'enabled' must be boolean"));
        }
        WtxStrategyState updated = wtxStrategyService.updateTelegramNotifications(instrument, timeframe, enabled);
        return ResponseEntity.ok(toStateView(updated));
    }

    private Map<String, Object> toStateView(WtxStrategyState state) {
        Map<String, Object> view = new HashMap<>();
        view.put("instrument", state.instrument());
        view.put("timeframe", state.timeframe());
        view.put("currentDirection", state.currentPosition().name());
        view.put("dailyPnl", state.dailyPnl());
        view.put("dayStartEquity", state.dayStartEquity());
        view.put("currentEquity", state.currentEquity());
        view.put("maxDailyLossUsd", wtxStrategyService.getMaxDailyLossUsd());
        view.put("maxLossHit", state.maxLossHit());
        WtxProfile profile = state.activeProfile() != null ? state.activeProfile() : WtxProfile.BASELINE;
        view.put("activeProfile", profile.name());
        view.put("autoExecutionEnabled", state.autoExecutionEnabled());
        view.put("swingBiasFilterEnabled", state.swingBiasFilterEnabled());
        view.put("currentSwingBias", wtxStrategyService.currentSwingBias(state.instrument(), state.timeframe()));
        view.put("regime", wtxStrategyService.currentRegime(state.instrument(), state.timeframe()));
        view.put("configuredOrderQty", state.configuredOrderQty());
        view.put("telegramNotificationsEnabled", state.telegramNotificationsEnabled());
        // Open-position summary (null/zero when FLAT). entryPrice / entryQty come straight from
        // state; "stopLoss" surfaces the live trailing-exit stop (initial ATR stop until the
        // trailing phase arms, then the ratcheted trailing level).
        view.put("entryPrice", state.entryPrice());
        view.put("entryQty", state.entryQty());
        view.put("stopLoss", wtxStrategyService.effectiveStop(state));
        view.put("canTrade", !state.maxLossHit() || !profile.blocksOnMaxLoss());
        return view;
    }

    private Map<String, Object> defaultStateView(String instrument, String timeframe) {
        Map<String, Object> view = new HashMap<>();
        view.put("instrument", instrument);
        view.put("timeframe", timeframe);
        view.put("currentDirection", "FLAT");
        view.put("dailyPnl", 0);
        view.put("dayStartEquity", wtxStrategyService.getInitialEquity());
        view.put("currentEquity", wtxStrategyService.getInitialEquity());
        view.put("maxDailyLossUsd", wtxStrategyService.getMaxDailyLossUsd());
        view.put("maxLossHit", false);
        view.put("activeProfile", WtxProfile.BASELINE.name());
        view.put("autoExecutionEnabled", false);
        view.put("swingBiasFilterEnabled", false);
        view.put("currentSwingBias", null);
        view.put("regime", wtxStrategyService.currentRegime(instrument, timeframe));
        view.put("configuredOrderQty", com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState.DEFAULT_ORDER_QTY);
        view.put("telegramNotificationsEnabled", WtxStrategyState.defaultTelegramEnabledFor(instrument));
        view.put("entryPrice", null);
        view.put("entryQty", 0);
        view.put("stopLoss", null);
        view.put("canTrade", true);
        return view;
    }

    private Map<String, Object> toSignalView(WtxSignal signal) {
        Map<String, Object> view = new HashMap<>();
        view.put("instrument", signal.instrument());
        view.put("timeframe", signal.timeframe());
        view.put("signalType", signal.signalType().name());
        view.put("direction", signal.direction());
        view.put("wt1Value", signal.wt1Value());
        view.put("wt2Value", signal.wt2Value());
        view.put("canTrade", signal.canTrade());
        view.put("actionTaken", signal.suggestedAction().name());
        view.put("enrichment", signal.enrichment());
        view.put("signalTs", signal.signalTs().toString());
        view.put("routingOutcome", signal.routingOutcome() != null ? signal.routingOutcome().name() : null);
        view.put("routingErrorMessage", signal.routingErrorMessage());
        view.put("price", signal.price());
        view.put("exitType", signal.exitType() != null ? signal.exitType().name() : null);
        return view;
    }
}
