package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.TradeDecisionService;
import com.riskdesk.domain.decision.model.TradeDecision;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-side REST endpoints for {@link TradeDecision}. The UI (PR 2) will consume these
 * instead of {@code /api/mentor/auto-alerts/*} once the cutover is done.
 *
 * <p>Kept read-only in PR 1. Re-analysis and manual triggers are added in PR 2 together
 * with the frontend switch so the two PRs don't partially diverge.
 */
@RestController
@RequestMapping("/api/decisions")
@CrossOrigin(origins = "*")
public class TradeDecisionController {

    private final TradeDecisionService service;

    public TradeDecisionController(TradeDecisionService service) {
        this.service = service;
    }

    /** Most recent decisions, newest first. */
    @GetMapping("/recent")
    public List<TradeDecision> recent(@RequestParam(defaultValue = "100") int limit) {
        return service.recent(limit);
    }

    /** Recent decisions filtered by instrument. */
    @GetMapping("/by-instrument/{instrument}")
    public List<TradeDecision> byInstrument(@PathVariable String instrument,
                                            @RequestParam(defaultValue = "100") int limit) {
        return service.recentByInstrument(instrument, limit);
    }

    /** Single decision by id. */
    @GetMapping("/{id}")
    public ResponseEntity<TradeDecision> byId(@PathVariable Long id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** All revisions for a given (instrument, timeframe, direction, zone) thread. */
    @GetMapping("/thread")
    public List<TradeDecision> thread(@RequestParam String instrument,
                                       @RequestParam String timeframe,
                                       @RequestParam String direction,
                                       @RequestParam String zoneName) {
        return service.thread(instrument, timeframe, direction, zoneName);
    }
}
