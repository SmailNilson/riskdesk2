package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.RolloverDetectionService;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.model.Instrument;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for futures contract rollover management.
 *
 * GET  /api/rollover/status          — current contract month + days-to-expiry per instrument
 * POST /api/rollover/confirm         — trader confirms the rollover to a new contract month
 */
@RestController
@RequestMapping("/api/rollover")
@CrossOrigin
public class RolloverController {

    private final RolloverDetectionService  detectionService;
    private final ActiveContractRegistry    contractRegistry;

    public RolloverController(RolloverDetectionService detectionService,
                              ActiveContractRegistry contractRegistry) {
        this.detectionService = detectionService;
        this.contractRegistry = contractRegistry;
    }

    /**
     * Returns active contract months and rollover risk for each instrument.
     * Used by the frontend RolloverBanner to decide whether to show a warning.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, String> active = contractRegistry.snapshot().entrySet().stream()
            .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));

        return ResponseEntity.ok(Map.of(
            "activeContracts", active,
            "rolloverStatus",  detectionService.getCurrentStatus()
        ));
    }

    /**
     * Trader confirms a rollover: atomically switches the active contract month
     * for one instrument and clears the IBKR contract cache.
     *
     * After this call:
     *   - All indicators, candle writes, and mentor payloads use the new contract month
     *   - A fresh IBKR contract resolution is triggered for the instrument
     *
     * @param instrument   e.g. "MGC"
     * @param contractMonth e.g. "202608"
     */
    @PostMapping("/confirm")
    public ResponseEntity<Map<String, Object>> confirmRollover(
            @RequestParam String instrument,
            @RequestParam String contractMonth) {

        Instrument inst;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + instrument));
        }

        if (!inst.isExchangeTradedFuture()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Synthetic instruments do not support rollover: " + instrument));
        }

        if (!contractMonth.matches("\\d{6}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "contractMonth must be YYYYMM format"));
        }

        detectionService.confirmRollover(inst, contractMonth);

        return ResponseEntity.ok(Map.of(
            "instrument",    inst.name(),
            "contractMonth", contractMonth,
            "status",        "ROLLOVER_CONFIRMED"
        ));
    }
}
