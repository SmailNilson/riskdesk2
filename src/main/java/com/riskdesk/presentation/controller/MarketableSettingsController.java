package com.riskdesk.presentation.controller;

import com.riskdesk.application.dto.MarketableSettingsUpdateRequest;
import com.riskdesk.application.dto.MarketableSettingsView;
import com.riskdesk.application.service.MarketableExecutionSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Operator control for the GLOBAL marketable-execution policy (exits + reverse open). Like the Auto-IBKR
 * toggle, but execution-core-wide: a UI switch flips it live with no redeploy.
 */
@RestController
@RequestMapping("/api/execution/marketable-settings")
public class MarketableSettingsController {

    private final MarketableExecutionSettingsService service;

    public MarketableSettingsController(MarketableExecutionSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public MarketableSettingsView get() {
        return MarketableSettingsView.from(service.current());
    }

    @PutMapping
    public ResponseEntity<?> update(@RequestBody(required = false) MarketableSettingsUpdateRequest request) {
        MarketableSettingsUpdateRequest body = request != null ? request
            : new MarketableSettingsUpdateRequest(null, null, null);
        try {
            return ResponseEntity.ok(MarketableSettingsView.from(
                service.update(body.closeEnabled(), body.reverseOpenEnabled(), body.crossTicks())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
