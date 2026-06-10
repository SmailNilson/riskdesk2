package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.OrderFlowHistoryService;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.domain.orderflow.port.FootprintPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for the Footprint Chart engine (UC-OF-011).
 * Exposes the current clock-aligned footprint bar and the persisted bar history.
 * Bar duration and price-bucket sizes are fixed by configuration
 * ({@code riskdesk.order-flow.footprint.*}).
 */
@RestController
@RequestMapping("/api/order-flow/footprint")
public class FootprintController {

    private final ObjectProvider<FootprintPort> footprintPortProvider;
    private final OrderFlowHistoryService historyService;

    public FootprintController(ObjectProvider<FootprintPort> footprintPortProvider,
                               OrderFlowHistoryService historyService) {
        this.footprintPortProvider = footprintPortProvider;
        this.historyService = historyService;
    }

    /**
     * GET /api/order-flow/footprint/{instrument}
     * Returns the current (in-progress) clock-aligned footprint bar as JSON.
     * The legacy {@code timeframe} query param is accepted but ignored — the bar
     * duration is configured server-side.
     */
    @GetMapping("/{instrument}")
    public ResponseEntity<?> getFootprint(
            @PathVariable String instrument,
            @RequestParam(required = false) String timeframe) {

        FootprintPort footprintPort = footprintPortProvider.getIfAvailable();
        if (footprintPort == null) {
            return ResponseEntity.ok(Map.of("error", "Footprint data not available"));
        }

        try {
            Instrument inst = Instrument.valueOf(instrument.toUpperCase());
            Optional<FootprintBar> bar = footprintPort.currentBar(inst);

            if (bar.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "instrument", inst.name(),
                    "available", false
                ));
            }

            return ResponseEntity.ok(bar.get());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "Unknown instrument: " + instrument));
        }
    }

    /**
     * GET /api/order-flow/footprint/{instrument}/history?bars=12
     * Returns the most recent closed footprint bars, newest first.
     */
    @GetMapping("/{instrument}/history")
    public ResponseEntity<?> getFootprintHistory(
            @PathVariable String instrument,
            @RequestParam(defaultValue = "12") int bars) {

        try {
            Instrument inst = Instrument.valueOf(instrument.toUpperCase());
            List<FootprintBar> history = historyService.recentFootprintBars(inst, bars);
            return ResponseEntity.ok(history);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "Unknown instrument: " + instrument));
        }
    }
}
