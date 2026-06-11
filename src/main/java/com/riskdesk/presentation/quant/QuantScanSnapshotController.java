package com.riskdesk.presentation.quant;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.scanlog.QuantScanSnapshot;
import com.riskdesk.domain.quant.scanlog.QuantScanSnapshotPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Read surface for the per-scan Quant flow log (`quant_scan_snapshots`).
 *
 * <p>{@code GET /api/quant/scan-log/{instrument}?from&amp;to&amp;limit} — rows
 * newest first. Defaults to the last 2 hours when no window is given. This is
 * the series that feeds gate-replay backtests and threshold sweeps; the
 * endpoint exists for verification and ad-hoc analysis.</p>
 */
@RestController
@RequestMapping("/api/quant/scan-log")
public class QuantScanSnapshotController {

    private static final Duration DEFAULT_WINDOW = Duration.ofHours(2);
    private static final int MAX_LIMIT = 5000;

    private final QuantScanSnapshotPort scanLog;

    public QuantScanSnapshotController(QuantScanSnapshotPort scanLog) {
        this.scanLog = scanLog;
    }

    @GetMapping("/{instrument}")
    public ResponseEntity<Map<String, Object>> range(
            @PathVariable String instrument,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "500") int limit) {

        Instrument inst;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown instrument: " + instrument));
        }

        Instant toTs;
        Instant fromTs;
        try {
            toTs = to == null || to.isBlank() ? Instant.now() : Instant.parse(to);
            fromTs = from == null || from.isBlank() ? toTs.minus(DEFAULT_WINDOW) : Instant.parse(from);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid 'from'/'to'; use ISO-8601 (e.g. 2026-06-11T00:00:00Z)."));
        }
        if (!fromTs.isBefore(toTs)) {
            return ResponseEntity.badRequest().body(Map.of("error", "'from' must be strictly before 'to'."));
        }

        int pageSize = Math.min(Math.max(1, limit), MAX_LIMIT);
        List<QuantScanSnapshot> rows = scanLog.findRange(inst, fromTs, toTs, pageSize);
        return ResponseEntity.ok(Map.of(
            "instrument", inst.name(),
            "from", fromTs.toString(),
            "to", toTs.toString(),
            "count", rows.size(),
            "snapshots", rows));
    }
}
