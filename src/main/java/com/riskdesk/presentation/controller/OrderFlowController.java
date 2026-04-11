package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.OrderFlowOrchestrator;
import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Instrument;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for the Order Flow subsystem.
 */
@RestController
@RequestMapping("/api/order-flow")
public class OrderFlowController {

    private final ObjectProvider<OrderFlowOrchestrator> orchestratorProvider;
    private final ObjectProvider<TickDataPort> tickDataPortProvider;

    public OrderFlowController(ObjectProvider<OrderFlowOrchestrator> orchestratorProvider,
                                ObjectProvider<TickDataPort> tickDataPortProvider) {
        this.orchestratorProvider = orchestratorProvider;
        this.tickDataPortProvider = tickDataPortProvider;
    }

    /**
     * GET /api/order-flow/status
     * Returns the current state of all order flow subscriptions.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        OrderFlowOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null) {
            Map<String, Object> disabled = new LinkedHashMap<>();
            disabled.put("enabled", false);
            disabled.put("reason", "IBKR not enabled or not in IB_GATEWAY mode");
            return ResponseEntity.ok(disabled);
        }
        return ResponseEntity.ok(orchestrator.getStatus());
    }

    /**
     * GET /api/order-flow/delta/{instrument}
     * Returns the current tick aggregation (real delta or CLV fallback) for the given instrument.
     */
    @GetMapping("/delta/{instrument}")
    public ResponseEntity<Map<String, Object>> getDelta(@PathVariable String instrument) {
        TickDataPort tickDataPort = tickDataPortProvider.getIfAvailable();
        if (tickDataPort == null) {
            return ResponseEntity.ok(Map.of("error", "TickDataPort not available"));
        }

        try {
            Instrument inst = Instrument.valueOf(instrument.toUpperCase());
            Optional<TickAggregation> agg = tickDataPort.currentAggregation(inst);

            if (agg.isEmpty()) {
                return ResponseEntity.ok(Map.of("instrument", inst.name(), "available", false));
            }

            TickAggregation a = agg.get();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instrument", inst.name());
            result.put("available", true);
            result.put("delta", a.delta());
            result.put("cumulativeDelta", a.cumulativeDelta());
            result.put("buyVolume", a.buyVolume());
            result.put("sellVolume", a.sellVolume());
            result.put("buyRatioPct", a.buyRatioPct());
            result.put("deltaTrend", a.deltaTrend());
            result.put("divergenceDetected", a.divergenceDetected());
            result.put("divergenceType", a.divergenceType());
            result.put("source", a.source());
            result.put("windowStart", a.windowStart() != null ? a.windowStart().toString() : null);
            result.put("windowEnd", a.windowEnd() != null ? a.windowEnd().toString() : null);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + instrument));
        }
    }
}
