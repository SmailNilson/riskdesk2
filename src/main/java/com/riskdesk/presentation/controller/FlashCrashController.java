package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.FlashCrashSimulationService;
import com.riskdesk.application.service.FlashCrashStatusService;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.FlashCrashThresholds;
import com.riskdesk.domain.orderflow.port.FlashCrashConfigPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST controller for Flash Crash configuration and simulation (UC-OF-006).
 */
@RestController
@RequestMapping("/api/order-flow/flash-crash")
public class FlashCrashController {

    private final ObjectProvider<FlashCrashConfigPort> configPortProvider;
    private final ObjectProvider<FlashCrashSimulationService> simulationServiceProvider;
    private final ObjectProvider<FlashCrashStatusService> statusServiceProvider;

    public FlashCrashController(ObjectProvider<FlashCrashConfigPort> configPortProvider,
                                ObjectProvider<FlashCrashSimulationService> simulationServiceProvider,
                                ObjectProvider<FlashCrashStatusService> statusServiceProvider) {
        this.configPortProvider = configPortProvider;
        this.simulationServiceProvider = simulationServiceProvider;
        this.statusServiceProvider = statusServiceProvider;
    }

    /**
     * GET /api/order-flow/flash-crash/config/{instrument}
     * Returns the persisted thresholds for this instrument, or defaults if none are saved.
     */
    @GetMapping("/config/{instrument}")
    public ResponseEntity<?> getConfig(@PathVariable String instrument) {
        try {
            Instrument inst = Instrument.valueOf(instrument.toUpperCase());
            FlashCrashConfigPort configPort = configPortProvider.getIfAvailable();
            if (configPort == null) {
                return ResponseEntity.ok(FlashCrashThresholds.defaults());
            }
            FlashCrashThresholds thresholds = configPort.loadThresholds(inst)
                    .orElse(FlashCrashThresholds.defaults());
            return ResponseEntity.ok(thresholds);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + instrument));
        }
    }

    /**
     * PUT /api/order-flow/flash-crash/config/{instrument}
     * Saves custom thresholds for this instrument.
     */
    @PutMapping("/config/{instrument}")
    public ResponseEntity<?> saveConfig(@PathVariable String instrument,
                                        @RequestBody FlashCrashThresholds thresholds) {
        try {
            Instrument inst = Instrument.valueOf(instrument.toUpperCase());
            FlashCrashConfigPort configPort = configPortProvider.getIfAvailable();
            if (configPort == null) {
                return ResponseEntity.ok(Map.of("error", "FlashCrashConfigPort not available"));
            }
            configPort.saveThresholds(inst, thresholds);
            return ResponseEntity.ok(thresholds);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + instrument));
        }
    }

    /**
     * POST /api/order-flow/flash-crash/config/{instrument}/reset
     * Deletes custom thresholds and returns defaults.
     */
    @PostMapping("/config/{instrument}/reset")
    public ResponseEntity<?> resetConfig(@PathVariable String instrument) {
        try {
            Instrument inst = Instrument.valueOf(instrument.toUpperCase());
            FlashCrashConfigPort configPort = configPortProvider.getIfAvailable();
            if (configPort != null) {
                configPort.deleteThresholds(inst);
            }
            return ResponseEntity.ok(FlashCrashThresholds.defaults());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + instrument));
        }
    }

    /**
     * POST /api/order-flow/flash-crash/simulate
     * Runs a historical flash crash simulation. Body fields:
     * - instrument (required)
     * - timeframe (required, e.g. "1m", "5m")
     * - from (required, ISO-8601 instant)
     * - to (required, ISO-8601 instant)
     * - thresholds (optional, uses persisted/defaults if null)
     */
    @PostMapping("/simulate")
    public ResponseEntity<?> simulate(@RequestBody SimulateRequest request) {
        FlashCrashSimulationService simulationService = simulationServiceProvider.getIfAvailable();
        if (simulationService == null) {
            return ResponseEntity.ok(Map.of("error", "FlashCrashSimulationService not available"));
        }

        try {
            Instrument inst = Instrument.valueOf(request.instrument().toUpperCase());
            Instant from = Instant.parse(request.from());
            Instant to = Instant.parse(request.to());

            FlashCrashSimulationService.SimulationResult result =
                    simulationService.simulate(inst, request.timeframe(), from, to, request.thresholds());

            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + request.instrument()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/order-flow/flash-crash/status
     * Returns the most recent FSM phase transition per instrument from the
     * persisted event log. This is the seed data the frontend needs on a fresh
     * page load — the {@code /topic/flash-crash} WebSocket stream only delivers
     * forward-going events, so without this endpoint a just-opened panel would
     * show "no data" even though the FSM had phase changes earlier in the day.
     * <p>
     * Shape per instrument matches the WebSocket payload loosely so the frontend
     * can reuse the same reducer: {@code instrument}, {@code phase} (current),
     * {@code previousPhase}, {@code conditionsMet}, {@code conditions} (empty —
     * individual condition booleans are not persisted), {@code reversalScore},
     * {@code timestamp}.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> perInstrument = new LinkedHashMap<>();
        FlashCrashStatusService service = statusServiceProvider.getIfAvailable();
        if (service != null) {
            service.latestPerInstrument().forEach(
                (inst, snap) -> perInstrument.put(inst.name(), snap.toMap())
            );
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instruments", perInstrument);
        return ResponseEntity.ok(body);
    }

    /**
     * GET /api/order-flow/flash-crash/status/{instrument}
     * Returns the most recent FSM phase transition for a single instrument, or
     * 404 if no event has ever been persisted for it (e.g. fresh deployment).
     */
    @GetMapping("/status/{instrument}")
    public ResponseEntity<?> getStatusForInstrument(@PathVariable String instrument) {
        Instrument inst;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + instrument));
        }
        FlashCrashStatusService service = statusServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.notFound().build();
        }
        return service.latestForInstrument(inst)
            .<ResponseEntity<?>>map(snap -> ResponseEntity.ok(snap.toMap()))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Request body for the simulate endpoint.
     */
    public record SimulateRequest(
        String instrument,
        String timeframe,
        String from,
        String to,
        FlashCrashThresholds thresholds
    ) {}
}
