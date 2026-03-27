package com.riskdesk.presentation.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.application.dto.IndicatorSeriesSnapshot;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.application.service.IndicatorService;
import com.riskdesk.application.service.WatchlistDashboardService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/indicators")
@CrossOrigin(origins = "*")
public class IndicatorController {

    private final IndicatorService indicatorService;
    private final WatchlistDashboardService watchlistDashboardService;

    public IndicatorController(IndicatorService indicatorService,
                               WatchlistDashboardService watchlistDashboardService) {
        this.indicatorService = indicatorService;
        this.watchlistDashboardService = watchlistDashboardService;
    }

    /**
     * GET /api/indicators/MCL/10m
     * Returns full indicator snapshot for an instrument + timeframe.
     */
    @GetMapping("/{instrument}/{timeframe}")
    public IndicatorSnapshot getIndicators(
            @PathVariable String instrument,
            @PathVariable String timeframe) {
        try {
            return indicatorService.computeSnapshot(Instrument.valueOf(instrument.toUpperCase()), timeframe);
        } catch (IllegalArgumentException ignored) {
            return watchlistDashboardService.computeSnapshot(instrument, timeframe);
        }
    }

    /**
     * GET /api/indicators/MCL/10m/series?limit=500
     * Returns chart-ready indicator series computed on the backend.
     */
    @GetMapping("/{instrument}/{timeframe}/series")
    public IndicatorSeriesSnapshot getIndicatorSeries(
            @PathVariable String instrument,
            @PathVariable String timeframe,
            @RequestParam(defaultValue = "500") @Min(1) @Max(1000) int limit) {
        try {
            return indicatorService.computeSeries(Instrument.valueOf(instrument.toUpperCase()), timeframe, limit);
        } catch (IllegalArgumentException ignored) {
            return watchlistDashboardService.computeSeries(instrument, timeframe, limit);
        }
    }
}
