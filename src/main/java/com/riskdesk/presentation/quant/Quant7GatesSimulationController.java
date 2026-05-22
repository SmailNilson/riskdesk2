package com.riskdesk.presentation.quant;

import com.riskdesk.application.quant.simulation.Quant7GatesSimulationService;
import com.riskdesk.presentation.quant.dto.Quant7GatesSimulationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only HTTP surface for the Quant 7-Gates simulation harness.
 *
 * <ul>
 *   <li>{@code GET /api/quant/simulations} — list every open + closed
 *       simulated trade, newest first.</li>
 *   <li>{@code GET /api/quant/simulations/open} — same, restricted to OPEN
 *       rows (useful for the live tracker badge).</li>
 *   <li>{@code GET /api/quant/simulations/stats} — aggregate win-rate and
 *       net P&amp;L across closed rows.</li>
 * </ul>
 *
 * <p>Live updates stream via {@code /topic/quant/simulations} (STOMP).
 */
@RestController
@RequestMapping("/api/quant/simulations")
public class Quant7GatesSimulationController {

    private final Quant7GatesSimulationService service;

    public Quant7GatesSimulationController(Quant7GatesSimulationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Quant7GatesSimulationResponse> listAll() {
        return service.listAll().stream().map(Quant7GatesSimulationResponse::from).toList();
    }

    @GetMapping("/open")
    public List<Quant7GatesSimulationResponse> listOpen() {
        return service.listOpen().stream().map(Quant7GatesSimulationResponse::from).toList();
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        Quant7GatesSimulationService.Stats s = service.stats();
        return new StatsResponse(
            s.closedCount(),
            s.wins(),
            s.losses(),
            s.winRatePct(),
            s.netPoints(),
            s.netUsd(),
            service.listOpen().size()
        );
    }

    /** Public-facing aggregate. {@code winRatePct} is null when no rows are decided yet. */
    public record StatsResponse(
        int closedCount,
        int wins,
        int losses,
        Double winRatePct,
        double netPoints,
        double netUsd,
        int openCount
    ) {}
}
