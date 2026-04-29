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
 * HTTP entry point for the Quant 7-gate evaluator.
 *
 * <ul>
 *   <li>{@code GET /api/quant/snapshot/{instrument}} — pure read: returns the
 *       latest snapshot the scheduler has produced. Never triggers a scan,
 *       never publishes to a STOMP topic. Returns 404 until the first
 *       scheduler tick (≤ 60 s after boot).</li>
 *   <li>{@code GET /api/quant/history/{instrument}?hours=N} — returns the
 *       in-memory ring buffer of past snapshots.</li>
 *   <li>{@code POST /api/quant/snapshot/{instrument}/refresh} — explicit
 *       on-demand scan. Side-effects (state write + WebSocket broadcast) are
 *       intentional here because the caller requested it.</li>
 *   <li>{@code POST /api/quant/ai-advice/{instrument}} — manual tier-2
 *       advisor call ("Ask AI" button).</li>
 * </ul>
 *
 * <p>The original {@code GET} previously called {@code service.scan(...)},
 * which meant every dashboard open broadcast a fresh snapshot to every
 * connected user and could fire 6/7 / 7/7 alerts triggered by a page load
 * rather than a real signal transition (PR #297 review feedback, P1).</p>
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
        QuantSnapshot snap = service.latestSnapshot(inst);
        if (snap == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(QuantSnapshotResponse.from(snap));
    }

    /**
     * Explicit on-demand scan. Use this when the caller (a human clicking a
     * "refresh" button, an integration test) wants the side effects — a fresh
     * state mutation and a WebSocket broadcast.
     */
    @PostMapping("/snapshot/{instrument}/refresh")
    public ResponseEntity<QuantSnapshotResponse> refresh(@PathVariable String instrument) {
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
