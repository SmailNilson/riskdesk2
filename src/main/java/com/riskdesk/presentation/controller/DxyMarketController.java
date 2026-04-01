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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

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
    public ResponseEntity<DxySnapshotView> latest() {
        ensureSupported();
        return dxyMarketService.latestView()
            .map(ResponseEntity::ok)
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
