package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.AlertService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/alerts")
@CrossOrigin(origins = "*")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping("/recent")
    public List<Map<String, Object>> getRecentAlerts() {
        return alertService.getRecentAlerts();
    }

    /**
     * Snoozes a specific alert key for the given duration.
     * Body: { "key": "ema:golden:MCL:10m", "durationSeconds": 300 }
     */
    @PostMapping("/snooze")
    public ResponseEntity<Void> snoozeAlert(@RequestBody Map<String, Object> body) {
        String key = (String) body.get("key");
        long durationSeconds = ((Number) body.get("durationSeconds")).longValue();
        alertService.snoozeAlert(key, durationSeconds);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/muted-timeframes")
    public Set<String> getMutedTimeframes() {
        return alertService.getMutedTimeframes();
    }

    @PutMapping("/muted-timeframes/{timeframe}")
    public ResponseEntity<Void> setTimeframeMute(
            @PathVariable String timeframe,
            @RequestParam boolean muted) {
        if (muted) {
            alertService.muteTimeframe(timeframe);
        } else {
            alertService.unmuteTimeframe(timeframe);
        }
        return ResponseEntity.noContent().build();
    }
}
