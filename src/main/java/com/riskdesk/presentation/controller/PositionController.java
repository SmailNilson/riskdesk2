package com.riskdesk.presentation.controller;

import com.riskdesk.application.dto.PortfolioSummary;
import com.riskdesk.application.dto.PositionView;
import com.riskdesk.application.service.PositionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/positions")
@CrossOrigin(origins = "*")
public class PositionController {

    private final PositionService positionService;

    public PositionController(PositionService positionService) {
        this.positionService = positionService;
    }

    @GetMapping
    public List<PositionView> getOpenPositions(@RequestParam(required = false) String accountId) {
        return positionService.getOpenPositions(accountId);
    }

    @GetMapping("/closed")
    public List<PositionView> getClosedPositions() {
        return positionService.getClosedPositions();
    }

    @GetMapping("/summary")
    public PortfolioSummary getPortfolioSummary(@RequestParam(required = false) String accountId) {
        return positionService.getPortfolioSummary(accountId);
    }
}
