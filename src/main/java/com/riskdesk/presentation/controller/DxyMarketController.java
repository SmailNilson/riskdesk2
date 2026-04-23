package com.riskdesk.presentation.controller;

import com.riskdesk.application.dto.DxyHealthView;
import com.riskdesk.application.dto.DxySnapshotView;
import com.riskdesk.application.service.DxyMarketService;
import com.riskdesk.domain.marketdata.model.DxySnapshot;
import com.riskdesk.domain.marketdata.model.FxComponentContribution;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@RestController
@RequestMapping("/api/market/dxy")
@CrossOrigin(origins = "*")
public class DxyMarketController {

    private final DxyMarketService dxyMarketService;

    public DxyMarketController(DxyMarketService dxyMarketService) {
        this.dxyMarketService = dxyMarketService;
    }

    @GetMapping("/latest")
    public ResponseEntity<Map<String, Object>> latest() {
        ensureSupported();
        return dxyMarketService.latestResolvedSnapshot()
            .map(resolved -> {
                DxySnapshot current = resolved.snapshot();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("timestamp", current.timestamp());
                body.put("eurusd", current.eurusd());
                body.put("usdjpy", current.usdjpy());
                body.put("gbpusd", current.gbpusd());
                body.put("usdcad", current.usdcad());
                body.put("usdsek", current.usdsek());
                body.put("usdchf", current.usdchf());
                body.put("dxyValue", current.dxyValue());
                body.put("source", resolved.servedSource());
                body.put("isComplete", current.complete());

                // % change vs session baseline (IBKR close or session-open fallback)
                DxySnapshot baseline = dxyMarketService.findBaselineSnapshot(current.timestamp()).orElse(null);
                if (baseline != null && baseline.dxyValue() != null
                        && baseline.dxyValue().compareTo(BigDecimal.ZERO) > 0
                        && current.dxyValue() != null) {
                    BigDecimal pct = current.dxyValue().subtract(baseline.dxyValue())
                        .divide(baseline.dxyValue(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(3, RoundingMode.HALF_UP);
                    body.put("changePercent", pct);
                    body.put("baselineValue", baseline.dxyValue());
                    body.put("baselineTimestamp", baseline.timestamp());
                }

                return ResponseEntity.ok(body);
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/history")
    public ResponseEntity<List<DxySnapshotView>> history(@RequestParam String from,
                                                         @RequestParam String to) {
        ensureSupported();
        Instant fromInstant = parseInstant("from", from);
        Instant toInstant = parseInstant("to", to);
        if (!fromInstant.isBefore(toInstant)) {
            throw new ResponseStatusException(BAD_REQUEST, "from must be strictly before to");
        }
        return ResponseEntity.ok(dxyMarketService.history(fromInstant, toInstant));
    }

    @GetMapping("/health")
    public DxyHealthView health() {
        return dxyMarketService.health();
    }

    @GetMapping("/breakdown")
    public ResponseEntity<List<FxComponentContribution>> breakdown() {
        ensureSupported();
        DxySnapshot current = dxyMarketService.latestSnapshot().orElse(null);
        if (current == null) {
            return ResponseEntity.notFound().build();
        }
        DxySnapshot baseline = dxyMarketService.findBaselineSnapshot(current.timestamp()).orElse(null);
        if (baseline == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dxyMarketService.computeComponentContributions(current, baseline));
    }

    private void ensureSupported() {
        if (!dxyMarketService.supported()) {
            throw new ResponseStatusException(
                SERVICE_UNAVAILABLE,
                "Synthetic DXY endpoints are unavailable outside IB_GATEWAY mode"
            );
        }
    }

    private Instant parseInstant(String field, String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(BAD_REQUEST, field + " must be a valid ISO-8601 instant", e);
        }
    }
}
