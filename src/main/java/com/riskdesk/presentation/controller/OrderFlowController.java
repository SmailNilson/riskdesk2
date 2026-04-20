package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.OrderFlowHistoryService;
import com.riskdesk.application.service.OrderFlowOrchestrator;
import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.port.MarketDepthPort;
import com.riskdesk.application.dto.AbsorptionEventView;
import com.riskdesk.application.dto.IcebergEventView;
import com.riskdesk.application.dto.SpoofingEventView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for the Order Flow subsystem.
 */
@RestController
@RequestMapping("/api/order-flow")
public class OrderFlowController {

    private static final int DEFAULT_HISTORY_LIMIT = 20;

    private final ObjectProvider<OrderFlowOrchestrator> orchestratorProvider;
    private final ObjectProvider<TickDataPort> tickDataPortProvider;
    private final ObjectProvider<MarketDepthPort> depthPortProvider;
    private final OrderFlowHistoryService historyService;

    public OrderFlowController(ObjectProvider<OrderFlowOrchestrator> orchestratorProvider,
                                ObjectProvider<TickDataPort> tickDataPortProvider,
                                ObjectProvider<MarketDepthPort> depthPortProvider,
                                OrderFlowHistoryService historyService) {
        this.orchestratorProvider = orchestratorProvider;
        this.tickDataPortProvider = tickDataPortProvider;
        this.depthPortProvider = depthPortProvider;
        this.historyService = historyService;
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

    /**
     * GET /api/order-flow/depth/{instrument}
     * Returns the current market depth metrics for the given instrument.
     */
    @GetMapping("/depth/{instrument}")
    public ResponseEntity<Map<String, Object>> getDepth(@PathVariable String instrument) {
        MarketDepthPort depthPort = depthPortProvider.getIfAvailable();
        if (depthPort == null) {
            return ResponseEntity.ok(Map.of("error", "MarketDepthPort not available"));
        }

        try {
            Instrument inst = Instrument.valueOf(instrument.toUpperCase());
            java.util.Optional<DepthMetrics> depth = depthPort.currentDepth(inst);

            if (depth.isEmpty()) {
                return ResponseEntity.ok(Map.of("instrument", inst.name(), "available", false));
            }

            DepthMetrics d = depth.get();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instrument", inst.name());
            result.put("available", true);
            result.put("totalBidSize", d.totalBidSize());
            result.put("totalAskSize", d.totalAskSize());
            result.put("depthImbalance", d.depthImbalance());
            result.put("bestBid", d.bestBid());
            result.put("bestAsk", d.bestAsk());
            result.put("spread", d.spread());
            result.put("spreadTicks", d.spreadTicks());
            if (d.bidWall() != null) {
                result.put("bidWall", Map.of("price", d.bidWall().price(), "size", d.bidWall().size()));
            }
            if (d.askWall() != null) {
                result.put("askWall", Map.of("price", d.askWall().price(), "size", d.askWall().size()));
            }
            result.put("timestamp", d.timestamp() != null ? d.timestamp().toString() : null);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + instrument));
        }
    }

    // ---------------------------------------------------------------------------
    // Historical endpoints — last N persisted events for the instrument.
    // Backed by order_flow_{iceberg,absorption,spoofing}_events (90-day retention).
    // ---------------------------------------------------------------------------

    /**
     * GET /api/order-flow/iceberg/{instrument}?limit=20
     * Returns the most recent iceberg detection events for the given instrument,
     * newest first.
     */
    @GetMapping("/iceberg/{instrument}")
    public ResponseEntity<?> getRecentIcebergs(
            @PathVariable String instrument,
            @RequestParam(name = "limit", required = false, defaultValue = "" + DEFAULT_HISTORY_LIMIT) int limit) {
        try {
            Instrument inst = Instrument.valueOf(instrument.toUpperCase());
            List<IcebergEventView> events = historyService.recentIcebergs(inst, limit);
            return ResponseEntity.ok(events);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + instrument));
        }
    }

    /**
     * GET /api/order-flow/absorption/{instrument}?limit=20
     * Returns the most recent absorption detection events for the given instrument,
     * newest first.
     */
    @GetMapping("/absorption/{instrument}")
    public ResponseEntity<?> getRecentAbsorptions(
            @PathVariable String instrument,
            @RequestParam(name = "limit", required = false, defaultValue = "" + DEFAULT_HISTORY_LIMIT) int limit) {
        try {
            Instrument inst = Instrument.valueOf(instrument.toUpperCase());
            List<AbsorptionEventView> events = historyService.recentAbsorptions(inst, limit);
            return ResponseEntity.ok(events);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + instrument));
        }
    }

    /**
     * GET /api/order-flow/spoofing/{instrument}?limit=20
     * Returns the most recent spoofing detection events for the given instrument,
     * newest first.
     */
    @GetMapping("/spoofing/{instrument}")
    public ResponseEntity<?> getRecentSpoofings(
            @PathVariable String instrument,
            @RequestParam(name = "limit", required = false, defaultValue = "" + DEFAULT_HISTORY_LIMIT) int limit) {
        try {
            Instrument inst = Instrument.valueOf(instrument.toUpperCase());
            List<SpoofingEventView> events = historyService.recentSpoofings(inst, limit);
            return ResponseEntity.ok(events);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + instrument));
        }
    }
}
