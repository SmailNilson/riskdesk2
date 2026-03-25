package com.riskdesk.presentation.controller;

import com.riskdesk.presentation.dto.*;
import com.riskdesk.domain.model.Position;
import com.riskdesk.application.service.PositionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PositionView openPosition(@Valid @RequestBody CreatePositionRequest request) {
        Position pos = positionService.openPosition(request);
        return PositionView.from(pos);
    }

    @PostMapping("/{id}/close")
    public PositionView closePosition(@PathVariable Long id, @Valid @RequestBody ClosePositionRequest request) {
        Position pos = positionService.closePosition(id, request);
        return PositionView.from(pos);
    }

    @GetMapping("/summary")
    public PortfolioSummary getPortfolioSummary(@RequestParam(required = false) String accountId) {
        return positionService.getPortfolioSummary(accountId);
    }
}
