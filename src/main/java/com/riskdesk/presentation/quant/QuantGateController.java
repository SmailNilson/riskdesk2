package com.riskdesk.presentation.quant;

import com.riskdesk.application.quant.service.QuantGateService;
import com.riskdesk.application.quant.service.QuantSnapshotHistoryStore;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.advisor.AiAdvice;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.presentation.quant.dto.QuantAdviceResponse;
import com.riskdesk.presentation.quant.dto.QuantSnapshotResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Read-only HTTP entry point for the Quant 7-gate evaluator.
 *
 * <ul>
 *   <li>{@code GET /api/quant/snapshot/{instrument}} — runs a fresh scan and returns the result.</li>
 * </ul>
 *
 * <p>The scheduled scanner publishes the same payload over
 * {@code /topic/quant/snapshot/{instrument}} every 60 s — this endpoint is
 * convenient for a manual refresh from the dashboard.</p>
 */
@RestController
@RequestMapping("/api/quant")
public class QuantGateController {

    private static final int DEFAULT_HISTORY_HOURS = 2;
    private static final int MAX_HISTORY_HOURS = 8;

    private final QuantGateService service;
    private final QuantSnapshotHistoryStore historyStore;

    public QuantGateController(QuantGateService service, QuantSnapshotHistoryStore historyStore) {
        this.service = service;
        this.historyStore = historyStore;
    }

    @GetMapping("/snapshot/{instrument}")
    public ResponseEntity<QuantSnapshotResponse> snapshot(@PathVariable String instrument) {
        Instrument inst;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        QuantSnapshot snap = service.scan(inst);
        return ResponseEntity.ok(QuantSnapshotResponse.from(snap));
    }

    @GetMapping("/history/{instrument}")
    public ResponseEntity<List<QuantSnapshotResponse>> history(
        @PathVariable String instrument,
        @RequestParam(name = "hours", required = false, defaultValue = "" + DEFAULT_HISTORY_HOURS) int hours
    ) {
        Instrument inst;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        int capped = Math.max(1, Math.min(hours, MAX_HISTORY_HOURS));
        List<QuantSnapshotResponse> body = historyStore.recent(inst, Duration.ofHours(capped))
            .stream()
            .map(QuantSnapshotResponse::from)
            .toList();
        return ResponseEntity.ok(body);
    }

    /**
     * Manually triggers a tier-2 AI advisor call for the latest snapshot of the
     * instrument. Returns immediately with the cached response if one is fresh.
     */
    @PostMapping("/ai-advice/{instrument}")
    public ResponseEntity<QuantAdviceResponse> aiAdvice(@PathVariable String instrument) {
        Instrument inst;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        AiAdvice advice = service.requestAdviceNow(inst);
        return ResponseEntity.ok(QuantAdviceResponse.from(inst, advice));
    }
}
