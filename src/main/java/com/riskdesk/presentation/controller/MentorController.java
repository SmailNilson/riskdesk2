package com.riskdesk.presentation.controller;

import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorIntermarketSnapshot;
import com.riskdesk.application.dto.MentorManualReview;
import com.riskdesk.application.dto.MentorSignalReview;
import com.riskdesk.application.dto.HistoricalTradesDTO;
import com.riskdesk.application.service.HistoricalDataService;
import com.riskdesk.application.service.HistoricalTradeImporterService;
import com.riskdesk.application.service.MentorAnalysisService;
import com.riskdesk.application.service.MentorIntermarketService;
import com.riskdesk.application.service.MentorMemoryService;
import com.riskdesk.application.service.MentorManualReviewService;
import com.riskdesk.application.service.MentorSignalReviewService;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.presentation.dto.HistoricalTradeImportFileRequest;
import com.riskdesk.presentation.dto.MentorAlertReviewRequest;
import com.riskdesk.presentation.dto.HistoricalTradeImportResponse;
import com.riskdesk.presentation.dto.MentorAnalyzeRequest;
import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/mentor")
@CrossOrigin(origins = "*")
public class MentorController {

    private final MentorAnalysisService mentorAnalysisService;
    private final MentorIntermarketService mentorIntermarketService;
    private final HistoricalDataService historicalDataService;
    private final HistoricalTradeImporterService historicalTradeImporterService;
    private final MentorMemoryService mentorMemoryService;
    private final MentorManualReviewService mentorManualReviewService;
    private final MentorSignalReviewService mentorSignalReviewService;

    public MentorController(MentorAnalysisService mentorAnalysisService,
                            MentorIntermarketService mentorIntermarketService,
                            HistoricalDataService historicalDataService,
                            HistoricalTradeImporterService historicalTradeImporterService,
                            MentorMemoryService mentorMemoryService,
                            MentorManualReviewService mentorManualReviewService,
                            MentorSignalReviewService mentorSignalReviewService) {
        this.mentorAnalysisService = mentorAnalysisService;
        this.mentorIntermarketService = mentorIntermarketService;
        this.historicalDataService = historicalDataService;
        this.historicalTradeImporterService = historicalTradeImporterService;
        this.mentorMemoryService = mentorMemoryService;
        this.mentorManualReviewService = mentorManualReviewService;
        this.mentorSignalReviewService = mentorSignalReviewService;
    }

    @PostMapping("/analyze")
    public MentorAnalyzeResponse analyze(@Valid @RequestBody MentorAnalyzeRequest request) {
        try {
            return mentorAnalysisService.analyze(request.payload());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
        }
    }

    @GetMapping("/manual-reviews/recent")
    public List<MentorManualReview> recentManualReviews() {
        return mentorManualReviewService.getRecentManualReviews();
    }

    @GetMapping("/intermarket")
    public MentorIntermarketSnapshot intermarket(@RequestParam(required = false) String instrument) {
        if (instrument == null || instrument.isBlank()) {
            return mentorIntermarketService.current(null);
        }

        try {
            return mentorIntermarketService.current(Instrument.valueOf(instrument.toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported instrument", e);
        }
    }

    @GetMapping("/auto-alerts/recent")
    public List<MentorSignalReview> recentAutoAlerts(@RequestParam(defaultValue = "500") int limit) {
        return mentorSignalReviewService.getRecentReviews(limit);
    }

    @PostMapping("/auto-alerts/thread")
    public List<MentorSignalReview> autoAlertThread(@Valid @RequestBody MentorAlertReviewRequest request) {
        try {
            return mentorSignalReviewService.getReviewsForAlert(buildAlert(request));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping("/auto-analysis/status")
    public Map<String, Object> autoAnalysisStatus() {
        return Map.of("enabled", mentorSignalReviewService.isAutoAnalysisEnabled());
    }

    @PostMapping("/auto-analysis/toggle")
    public Map<String, Object> toggleAutoAnalysis() {
        boolean next = !mentorSignalReviewService.isAutoAnalysisEnabled();
        mentorSignalReviewService.setAutoAnalysisEnabled(next);
        return Map.of("enabled", next);
    }

    @PostMapping("/auto-alerts/reanalyze")
    public MentorSignalReview reanalyzeExistingAlert(@Valid @RequestBody MentorAlertReviewRequest request) {
        try {
            return mentorSignalReviewService.reanalyzeAlert(buildAlert(request));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/refresh-context")
    public Map<String, Object> refreshContext(@RequestBody Map<String, String> request) {
        String instrumentRaw = request.get("instrument");
        String timeframe = request.get("timeframe");

        if (instrumentRaw == null || instrumentRaw.isBlank() || timeframe == null || timeframe.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "instrument and timeframe are required");
        }

        Instrument instrument;
        try {
            instrument = Instrument.valueOf(instrumentRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported instrument", e);
        }

        Set<String> refreshTargets = new LinkedHashSet<>(List.of(timeframe, "1h"));
        Map<String, Integer> refreshed = historicalDataService.refreshInstrumentContext(instrument, List.copyOf(refreshTargets));
        return Map.of(
            "instrument", instrument.name(),
            "refreshed", refreshed
        );
    }

    @PostMapping("/import-historical-trades")
    public HistoricalTradeImportResponse importHistoricalTrades(@Valid @RequestBody HistoricalTradesDTO payload) {
        HistoricalTradeImporterService.ImportSummary summary = historicalTradeImporterService.importTrades(payload);
        return new HistoricalTradeImportResponse(
            payload.instrument(),
            summary.imported(),
            summary.skipped(),
            summary.failed(),
            mentorMemoryService.currentStorageMode(),
            historicalTradeImporterService.currentEmbeddingModel(),
            "request_body"
        );
    }

    @PostMapping("/import-historical-trades/file")
    public HistoricalTradeImportResponse importHistoricalTradesFromFile(@Valid @RequestBody HistoricalTradeImportFileRequest request) {
        try {
            Path path = Path.of(request.filePath());
            HistoricalTradeImporterService.ImportSummary summary = historicalTradeImporterService.importFromFile(path);
            return new HistoricalTradeImportResponse(
                null,
                summary.imported(),
                summary.skipped(),
                summary.failed(),
                mentorMemoryService.currentStorageMode(),
                historicalTradeImporterService.currentEmbeddingModel(),
                path.toAbsolutePath().toString()
            );
        } catch (InvalidPathException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid filePath", e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private Alert buildAlert(MentorAlertReviewRequest request) {
        return new Alert(
            request.category().toLowerCase() + ":" + (request.instrument() == null || request.instrument().isBlank() ? "GLOBAL" : request.instrument().toUpperCase()) + ":" + request.timestamp(),
            AlertSeverity.valueOf(request.severity().toUpperCase()),
            request.message(),
            AlertCategory.valueOf(request.category().toUpperCase()),
            request.instrument(),
            Instant.parse(request.timestamp())
        );
    }
}
