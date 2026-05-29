package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.perfectsetup.PerfectSetupService;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.perfectsetup.PerfectSetupSignal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints exposing the latest Perfect Setup confluence signal per
 * instrument (used to seed the dashboard panel before the
 * {@code /topic/perfect-setup} WebSocket stream takes over).
 */
@RestController
@RequestMapping("/api/perfect-setup")
public class PerfectSetupController {

    private final PerfectSetupService service;

    public PerfectSetupController(PerfectSetupService service) {
        this.service = service;
    }

    /** GET /api/perfect-setup — latest signal for every evaluated instrument. */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> all() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PerfectSetupSignal s : service.latestAll()) {
            out.add(PerfectSetupService.toPayload(s));
        }
        return ResponseEntity.ok(out);
    }

    /** GET /api/perfect-setup/{instrument} — latest signal for one instrument. */
    @GetMapping("/{instrument}")
    public ResponseEntity<Map<String, Object>> forInstrument(@PathVariable String instrument) {
        Instrument inst;
        try {
            inst = Instrument.valueOf(instrument.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        PerfectSetupSignal s = service.latest(inst);
        if (s == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(PerfectSetupService.toPayload(s));
    }
}
