package com.riskdesk.presentation.controller;

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

import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for the Footprint Chart engine (UC-OF-011).
 * Exposes the current footprint bar for a given instrument and timeframe.
 */
@RestController
@RequestMapping("/api/order-flow/footprint")
public class FootprintController {

    private final ObjectProvider<FootprintPort> footprintPortProvider;

    public FootprintController(ObjectProvider<FootprintPort> footprintPortProvider) {
        this.footprintPortProvider = footprintPortProvider;
    }

    /**
     * GET /api/order-flow/footprint/{instrument}?timeframe=5m
     * Returns the current (in-progress) footprint bar as JSON.
     */
    @GetMapping("/{instrument}")
    public ResponseEntity<?> getFootprint(
            @PathVariable String instrument,
            @RequestParam(defaultValue = "5m") String timeframe) {

        FootprintPort footprintPort = footprintPortProvider.getIfAvailable();
        if (footprintPort == null) {
            return ResponseEntity.ok(Map.of("error", "Footprint data not available"));
        }

        try {
            Instrument inst = Instrument.valueOf(instrument.toUpperCase());
            Optional<FootprintBar> bar = footprintPort.currentBar(inst, timeframe);

            if (bar.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "instrument", inst.name(),
                    "timeframe", timeframe,
                    "available", false
                ));
            }

            return ResponseEntity.ok(bar.get());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "Unknown instrument: " + instrument));
        }
    }
}
