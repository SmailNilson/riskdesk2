package com.riskdesk.presentation.controller;

import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorIntermarketSnapshot;
import com.riskdesk.application.service.HistoricalDataService;
import com.riskdesk.application.service.MentorAnalysisService;
import com.riskdesk.application.service.MentorIntermarketService;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.presentation.dto.MentorAnalyzeRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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

    public MentorController(MentorAnalysisService mentorAnalysisService,
                            MentorIntermarketService mentorIntermarketService,
                            HistoricalDataService historicalDataService) {
        this.mentorAnalysisService = mentorAnalysisService;
        this.mentorIntermarketService = mentorIntermarketService;
        this.historicalDataService = historicalDataService;
    }

    @PostMapping("/analyze")
    public MentorAnalyzeResponse analyze(@Valid @RequestBody MentorAnalyzeRequest request) {
        try {
            return mentorAnalysisService.analyze(request.payload());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
        }
    }

    @GetMapping("/intermarket")
    public MentorIntermarketSnapshot intermarket() {
        return mentorIntermarketService.current();
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
}
