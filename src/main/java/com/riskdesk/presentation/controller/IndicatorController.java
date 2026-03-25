package com.riskdesk.presentation.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.riskdesk.presentation.dto.IndicatorSnapshot;
import com.riskdesk.presentation.dto.IndicatorSeriesSnapshot;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.application.service.IndicatorService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/indicators")
@CrossOrigin(origins = "*")
public class IndicatorController {

    private final IndicatorService indicatorService;

    public IndicatorController(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    /**
     * GET /api/indicators/MCL/10m
     * Returns full indicator snapshot for an instrument + timeframe.
     */
    @GetMapping("/{instrument}/{timeframe}")
    public IndicatorSnapshot getIndicators(
            @PathVariable Instrument instrument,
            @PathVariable String timeframe) {
        return indicatorService.computeSnapshot(instrument, timeframe);
    }

    /**
     * GET /api/indicators/MCL/10m/series?limit=500
     * Returns chart-ready indicator series computed on the backend.
     */
    @GetMapping("/{instrument}/{timeframe}/series")
    public IndicatorSeriesSnapshot getIndicatorSeries(
            @PathVariable Instrument instrument,
            @PathVariable String timeframe,
            @RequestParam(defaultValue = "500") @Min(1) @Max(1000) int limit) {
        return indicatorService.computeSeries(instrument, timeframe, limit);
    }
}
