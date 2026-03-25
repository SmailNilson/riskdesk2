package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.MentorAnalysisService;
import com.riskdesk.application.service.MentorIntermarketService;
import com.riskdesk.presentation.dto.MentorAnalyzeRequest;
import com.riskdesk.presentation.dto.MentorAnalyzeResponse;
import com.riskdesk.presentation.dto.MentorIntermarketSnapshot;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/mentor")
@CrossOrigin(origins = "*")
public class MentorController {

    private final MentorAnalysisService mentorAnalysisService;
    private final MentorIntermarketService mentorIntermarketService;

    public MentorController(MentorAnalysisService mentorAnalysisService,
                            MentorIntermarketService mentorIntermarketService) {
        this.mentorAnalysisService = mentorAnalysisService;
        this.mentorIntermarketService = mentorIntermarketService;
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
}
