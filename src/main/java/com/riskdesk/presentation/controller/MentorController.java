package com.riskdesk.presentation.controller;

import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorIntermarketSnapshot;
import com.riskdesk.application.dto.MentorManualReview;
import com.riskdesk.application.dto.MentorSignalReview;
import com.riskdesk.application.dto.TradeExecutionView;
import com.riskdesk.application.dto.HistoricalTradesDTO;
import com.riskdesk.application.service.CreateExecutionCommand;
import com.riskdesk.application.service.ExecutionManagerService;
import com.riskdesk.application.service.HistoricalDataService;
import com.riskdesk.application.service.HistoricalTradeImporterService;
import com.riskdesk.application.service.MentorAnalysisService;
import com.riskdesk.application.service.MentorIntermarketService;
import com.riskdesk.application.service.MentorMemoryService;
import com.riskdesk.application.service.MentorManualReviewService;
import com.riskdesk.application.service.MentorSignalReviewService;
import com.riskdesk.application.service.SubmitEntryOrderCommand;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.presentation.dto.CreateTradeExecutionRequest;
import com.riskdesk.presentation.dto.HistoricalTradeImportFileRequest;
import com.riskdesk.presentation.dto.MentorAlertReviewRequest;
import com.riskdesk.presentation.dto.HistoricalTradeImportResponse;
import com.riskdesk.presentation.dto.MentorAnalyzeRequest;
import com.riskdesk.presentation.dto.TradeExecutionLookupRequest;
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
    private final ExecutionManagerService executionManagerService;

    public MentorController(MentorAnalysisService mentorAnalysisService,
                            MentorIntermarketService mentorIntermarketService,
                            HistoricalDataService historicalDataService,
                            HistoricalTradeImporterService historicalTradeImporterService,
                            MentorMemoryService mentorMemoryService,
                            MentorManualReviewService mentorManualReviewService,
                            MentorSignalReviewService mentorSignalReviewService,
                            ExecutionManagerService executionManagerService) {
        this.mentorAnalysisService = mentorAnalysisService;
        this.mentorIntermarketService = mentorIntermarketService;
        this.historicalDataService = historicalDataService;
        this.historicalTradeImporterService = historicalTradeImporterService;
        this.mentorMemoryService = mentorMemoryService;
        this.mentorManualReviewService = mentorManualReviewService;
        this.mentorSignalReviewService = mentorSignalReviewService;
        this.executionManagerService = executionManagerService;
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
            return mentorSignalReviewService.reanalyzeAlert(
                buildAlert(request),
                request.entryPrice(),
                request.stopLoss(),
                request.takeProfit()
            );
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/executions")
    public TradeExecutionView createExecution(@Valid @RequestBody CreateTradeExecutionRequest request) {
        try {
            return TradeExecutionView.from(executionManagerService.ensureExecutionCreated(new CreateExecutionCommand(
                request.mentorSignalReviewId(),
                request.brokerAccountId(),
                request.quantity(),
                ExecutionTriggerSource.MANUAL_ARMING,
                Instant.now(),
                "mentor-panel"
            )));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    @GetMapping("/executions/by-review/{mentorSignalReviewId}")
    public TradeExecutionView executionByReview(@PathVariable Long mentorSignalReviewId) {
        return executionManagerService.findByMentorSignalReviewId(mentorSignalReviewId)
            .map(TradeExecutionView::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "trade execution not found"));
    }

    @PostMapping("/executions/by-review-ids")
    public List<TradeExecutionView> executionsByReviewIds(@Valid @RequestBody TradeExecutionLookupRequest request) {
        return executionManagerService.findByMentorSignalReviewIds(request.mentorSignalReviewIds()).stream()
            .map(TradeExecutionView::from)
            .toList();
    }

    @PostMapping("/executions/{executionId}/submit-entry")
    public TradeExecutionView submitEntryOrder(@PathVariable Long executionId) {
        try {
            return TradeExecutionView.from(executionManagerService.submitEntryOrder(new SubmitEntryOrderCommand(
                executionId,
                Instant.now(),
                "mentor-panel"
            )));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
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
