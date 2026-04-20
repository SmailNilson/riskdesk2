package com.riskdesk.presentation.controller;

import com.riskdesk.application.dto.TradeSimulationView;
import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.domain.simulation.TradeSimulation;
import com.riskdesk.domain.simulation.port.TradeSimulationRepositoryPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Read-side REST endpoints for the Phase 1 {@code trade_simulations} aggregate.
 *
 * <p>Backed by {@link TradeSimulationRepositoryPort} (the new Phase 1 port).
 * The legacy simulation fields on {@code MentorSignalReviewRecord} /
 * {@code MentorAudit} remain populated by {@code TradeSimulationService} until
 * Phase 3. Frontend continues to consume {@code /topic/mentor-alerts} for now;
 * Phase 2 will migrate the UI over to these endpoints + {@code /topic/simulations}.
 */
@RestController
@RequestMapping("/api/simulations")
@CrossOrigin(origins = "*")
public class SimulationController {

    private static final int DEFAULT_RECENT_LIMIT = 50;
    private static final int DEFAULT_INSTRUMENT_LIMIT = 20;

    private final TradeSimulationRepositoryPort simulationRepository;

    public SimulationController(TradeSimulationRepositoryPort simulationRepository) {
        this.simulationRepository = simulationRepository;
    }

    /** Most recent simulations, newest first. */
    @GetMapping("/recent")
    public List<TradeSimulationView> recent(
            @RequestParam(name = "limit", required = false, defaultValue = "" + DEFAULT_RECENT_LIMIT) int limit) {
        return simulationRepository.findRecent(limit).stream()
            .map(TradeSimulationView::from)
            .toList();
    }

    /** Recent simulations filtered by instrument code (case-insensitive). */
    @GetMapping("/by-instrument/{instrument}")
    public List<TradeSimulationView> byInstrument(
            @PathVariable String instrument,
            @RequestParam(name = "limit", required = false, defaultValue = "" + DEFAULT_INSTRUMENT_LIMIT) int limit) {
        if (instrument == null || instrument.isBlank()) {
            return List.of();
        }
        return simulationRepository.findByInstrument(instrument.toUpperCase(), limit).stream()
            .map(TradeSimulationView::from)
            .toList();
    }

    /** Single simulation by review id + type (defaults to SIGNAL). */
    @GetMapping("/by-review/{reviewId}")
    public ResponseEntity<TradeSimulationView> byReview(
            @PathVariable long reviewId,
            @RequestParam(name = "type", required = false, defaultValue = "SIGNAL") String type) {
        ReviewType reviewType;
        try {
            reviewType = ReviewType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        Optional<TradeSimulation> found = simulationRepository.findByReviewId(reviewId, reviewType);
        return found
            .map(TradeSimulationView::from)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
