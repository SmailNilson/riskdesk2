package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.CrossInstrumentAlertService;
import com.riskdesk.domain.engine.correlation.CorrelationState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST controller exposing the Oil-Nasdaq Inverse Momentum Scalp (ONIMS) engine status,
 * configuration, and signal history.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /api/correlation/oil-nasdaq/status}  — current engine state &amp; live parameters</li>
 *   <li>{@code GET  /api/correlation/oil-nasdaq/history} — recent confirmed signals (newest first)</li>
 *   <li>{@code POST /api/correlation/oil-nasdaq/config}  — update VIX threshold / blackout settings</li>
 *   <li>{@code POST /api/correlation/oil-nasdaq/blackout} — immediately activate an announcement blackout</li>
 *   <li>{@code POST /api/correlation/oil-nasdaq/reset}   — reset engine and clear history</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/correlation/oil-nasdaq")
@Tag(name = "Cross-Instrument Correlation", description = "ONIMS strategy engine status and configuration")
public class CorrelationController {

    private final CrossInstrumentAlertService correlationService;

    public CorrelationController(CrossInstrumentAlertService correlationService) {
        this.correlationService = correlationService;
    }

    // -----------------------------------------------------------------------
    // GET /status
    // -----------------------------------------------------------------------

    @GetMapping("/status")
    @Operation(summary = "Returns the current ONIMS engine state, VIX level, and active configuration")
    public ResponseEntity<Map<String, Object>> status() {
        CorrelationState state    = correlationService.currentState();
        Instant          blackout = correlationService.getBlackoutStart();
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("strategy",             "ONIMS");
        payload.put("engineState",          state.name());
        payload.put("vixThreshold",         correlationService.getVixThreshold());
        payload.put("cachedVixPrice",       correlationService.getCachedVixPrice());
        payload.put("blackoutActive",       isBlackoutActive(blackout, correlationService.getBlackoutDurationMinutes()));
        payload.put("blackoutStart",        blackout != null ? blackout.toString() : null);
        payload.put("blackoutDurationMins", correlationService.getBlackoutDurationMinutes());
        return ResponseEntity.ok(payload);
    }

    // -----------------------------------------------------------------------
    // GET /history
    // -----------------------------------------------------------------------

    @GetMapping("/history")
    @Operation(summary = "Returns recent confirmed ONIMS signals (newest first, max 100)")
    public ResponseEntity<List<Map<String, Object>>> history() {
        return ResponseEntity.ok(correlationService.getSignalHistory());
    }

    // -----------------------------------------------------------------------
    // POST /config
    // -----------------------------------------------------------------------

    @PostMapping("/config")
    @Operation(summary = "Updates ONIMS engine configuration (VIX threshold, blackout duration)")
    public ResponseEntity<Map<String, Object>> config(@RequestBody Map<String, Object> body) {
        if (body.containsKey("vixThreshold")) {
            double threshold = ((Number) body.get("vixThreshold")).doubleValue();
            correlationService.setVixThreshold(threshold);
        }
        if (body.containsKey("blackoutDurationMinutes")) {
            int minutes = ((Number) body.get("blackoutDurationMinutes")).intValue();
            correlationService.setBlackoutDurationMinutes(minutes);
        }
        return ResponseEntity.ok(Map.of(
                "status",               "updated",
                "vixThreshold",         correlationService.getVixThreshold(),
                "blackoutDurationMins", correlationService.getBlackoutDurationMinutes()
        ));
    }

    // -----------------------------------------------------------------------
    // POST /blackout
    // -----------------------------------------------------------------------

    @PostMapping("/blackout")
    @Operation(summary = "Activates an announcement blackout window starting now (OPEC+, EIA, etc.)")
    public ResponseEntity<Map<String, Object>> activateBlackout() {
        correlationService.activateBlackout();
        return ResponseEntity.ok(Map.of(
                "status",               "blackout_activated",
                "blackoutStart",        correlationService.getBlackoutStart().toString(),
                "blackoutDurationMins", correlationService.getBlackoutDurationMinutes()
        ));
    }

    // -----------------------------------------------------------------------
    // POST /reset
    // -----------------------------------------------------------------------

    @PostMapping("/reset")
    @Operation(summary = "Resets the ONIMS engine to IDLE and clears signal history")
    public ResponseEntity<Map<String, Object>> reset() {
        correlationService.reset();
        return ResponseEntity.ok(Map.of("status", "reset", "engineState", CorrelationState.IDLE.name()));
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private boolean isBlackoutActive(Instant start, int durationMinutes) {
        if (start == null) return false;
        long elapsed = java.time.Duration.between(start, Instant.now()).toMinutes();
        return elapsed >= 0 && elapsed < durationMinutes;
    }
}
