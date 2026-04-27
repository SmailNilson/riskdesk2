package com.riskdesk.presentation.controller;

import com.riskdesk.application.dto.analysis.LiveVerdictResponse;
import com.riskdesk.application.service.analysis.LiveVerdictService;
import com.riskdesk.application.service.analysis.ScoringReplayService;
import com.riskdesk.application.service.analysis.StaleSnapshotException;
import com.riskdesk.domain.analysis.model.LiveVerdict;
import com.riskdesk.domain.analysis.model.ScoringWeights;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;
import com.riskdesk.presentation.dto.ReplayRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * REST entrypoint for the tri-layer live analysis pipeline.
 * <p>
 * GET /api/analysis/live/{instrument}/{timeframe} → captures a fresh snapshot,
 * scores it, generates scenarios, persists, and returns the verdict.
 */
@RestController
@RequestMapping("/api/analysis")
public class LiveAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(LiveAnalysisController.class);

    private final LiveVerdictService verdictService;
    private final ScoringReplayService replayService;

    public LiveAnalysisController(LiveVerdictService verdictService,
                                    ScoringReplayService replayService) {
        this.verdictService = verdictService;
        this.replayService = replayService;
    }

    @GetMapping("/live/{instrument}/{timeframe}")
    public ResponseEntity<LiveVerdictResponse> live(@PathVariable String instrument,
                                                      @PathVariable String timeframe) {
        Instrument inst;
        Timeframe tf;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
            tf = Timeframe.fromLabel(timeframe);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        try {
            LiveVerdict verdict = verdictService.computeAndPublish(inst, tf);
            return ResponseEntity.ok(LiveVerdictResponse.from(verdict));
        } catch (StaleSnapshotException e) {
            log.info("Live analysis stale: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Snapshot stale, retry shortly: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("Live analysis failed for {}/{}: {}", inst, tf, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Live analysis failed: " + e.getMessage(), e);
        }
    }

    /**
     * GET /api/analysis/recent/{instrument}/{timeframe}?limit=20 — last N verdicts.
     */
    @GetMapping("/recent/{instrument}/{timeframe}")
    public ResponseEntity<List<LiveVerdictResponse>> recent(@PathVariable String instrument,
                                                              @PathVariable String timeframe,
                                                              @RequestParam(defaultValue = "20") int limit) {
        Instrument inst;
        Timeframe tf;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
            tf = Timeframe.fromLabel(timeframe);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        var verdicts = verdictService.findRecent(inst, tf, limit);
        return ResponseEntity.ok(verdicts.stream().map(LiveVerdictResponse::from).toList());
    }

    /**
     * POST /api/analysis/replay — re-score historical snapshots with candidate weights.
     */
    @PostMapping("/replay")
    public ResponseEntity<ScoringReplayService.ReplayReport> replay(@Valid @RequestBody ReplayRequest req) {
        Instrument inst;
        Timeframe tf;
        try {
            inst = Instrument.valueOf(req.instrument().toUpperCase());
            tf = Timeframe.fromLabel(req.timeframe());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        ScoringWeights weights;
        try {
            weights = new ScoringWeights(req.structure(), req.orderFlow(), req.momentum());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        try {
            return ResponseEntity.ok(replayService.replay(inst, tf, req.from(), req.to(), weights));
        } catch (IllegalArgumentException e) {
            // Out-of-range window or invalid bounds — surfaced as 400 so the
            // client can correct the request rather than retrying blindly.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
