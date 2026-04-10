package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignalPreFilterServiceTest {

    private final SignalPreFilterService service = new SignalPreFilterService();

    // ── HTF Trend Filter: weak signals (weight < 3.0) are blocked on LTF ──

    @Test
    void htfTrendFilter_bullishBlocksWeakShortOnLtf() {
        // EMA Death Cross = weight 1.0 (weak), should be blocked
        Alert shortAlert = new Alert("ema:death:E6:10m", AlertSeverity.WARNING,
                "E6 [10m] - EMA Death Cross (9 x 50)", AlertCategory.EMA, "E6");

        List<Alert> filtered = service.filter(List.of(shortAlert), "10m", "BULLISH");

        assertEquals(0, filtered.size(), "BULLISH H1 trend should block weak SHORT signals on 10m");
    }

    @Test
    void htfTrendFilter_bearishBlocksWeakLongOnLtf() {
        // EMA Golden Cross = weight 1.0 (weak), should be blocked
        Alert longAlert = new Alert("ema:golden:MCL:10m", AlertSeverity.INFO,
                "MCL [10m] - EMA Golden Cross (9 x 50)", AlertCategory.EMA, "MCL");

        List<Alert> filtered = service.filter(List.of(longAlert), "10m", "BEARISH");

        assertEquals(0, filtered.size(), "BEARISH H1 trend should block weak LONG signals on 10m");
    }

    // ── Standalone signals (weight >= 3.0) bypass HTF filter on LTF ──

    @Test
    void htfTrendFilter_standaloneBypassesBullishOnLtf() {
        // WaveTrend = weight 3.0 (standalone), should pass even counter-trend
        Alert shortAlert = new Alert("wt:bearish:E6:10m", AlertSeverity.WARNING,
                "E6 [10m] - WaveTrend Bearish Cross", AlertCategory.WAVETREND, "E6");

        List<Alert> filtered = service.filter(List.of(shortAlert), "10m", "BULLISH");

        assertEquals(1, filtered.size(), "Standalone SHORT signal should bypass BULLISH HTF filter on 10m");
    }

    @Test
    void htfTrendFilter_standaloneBypassesBearishOnLtf() {
        // WaveTrend = weight 3.0 (standalone), should pass even counter-trend
        Alert longAlert = new Alert("wt:bullish:MCL:10m", AlertSeverity.WARNING,
                "MCL [10m] - WaveTrend Bullish Cross", AlertCategory.WAVETREND, "MCL");

        List<Alert> filtered = service.filter(List.of(longAlert), "10m", "BEARISH");

        assertEquals(1, filtered.size(), "Standalone LONG signal should bypass BEARISH HTF filter on 10m");
    }

    @Test
    void htfTrendFilter_undefinedAllowsBothDirections() {
        Alert longAlert = new Alert("ema:golden:MCL:10m", AlertSeverity.INFO,
                "MCL [10m] - EMA Golden Cross (9 x 50)", AlertCategory.EMA, "MCL");
        Alert shortAlert = new Alert("ema:death:MCL:10m", AlertSeverity.WARNING,
                "MCL [10m] - EMA Death Cross (9 x 50)", AlertCategory.EMA, "MCL");

        // No chop registration — test HTF filter only
        List<Alert> filteredLong = service.filter(List.of(longAlert), "10m", "UNDEFINED");
        assertEquals(1, filteredLong.size(), "UNDEFINED trend should allow LONG");

        SignalPreFilterService service2 = new SignalPreFilterService();
        List<Alert> filteredShort = service2.filter(List.of(shortAlert), "10m", "UNDEFINED");
        assertEquals(1, filteredShort.size(), "UNDEFINED trend should allow SHORT");
    }

    @Test
    void htfTrendFilter_trendAlignedSignalAllowed() {
        Alert longAlert = new Alert("ema:golden:MGC:10m", AlertSeverity.INFO,
                "MGC [10m] - EMA Golden Cross (9 x 50)", AlertCategory.EMA, "MGC");

        List<Alert> filtered = service.filter(List.of(longAlert), "10m", "BULLISH");

        assertEquals(1, filtered.size(), "BULLISH H1 trend should allow LONG signals");
    }

    // ── H1 and 4h are NOT treated as LTF — HTF filter does not apply ──

    @Test
    void htfTrendFilter_notAppliedOn1h() {
        Alert shortAlert = new Alert("ema:death:MCL:1h", AlertSeverity.WARNING,
                "MCL [1h] - EMA Death Cross (9 x 50)", AlertCategory.EMA, "MCL");

        List<Alert> filtered = service.filter(List.of(shortAlert), "1h", "BULLISH");

        assertEquals(1, filtered.size(), "HTF filter should not apply on 1h timeframe");
    }

    @Test
    void htfTrendFilter_notAppliedOn4h() {
        Alert shortAlert = new Alert("wt:bearish:MCL:4h", AlertSeverity.WARNING,
                "MCL [4h] - WaveTrend Bearish Cross", AlertCategory.WAVETREND, "MCL");

        List<Alert> filtered = service.filter(List.of(shortAlert), "4h", "BULLISH");

        assertEquals(1, filtered.size(), "HTF filter should not apply on 4h timeframe");
    }

    @Test
    void antiChop_blocksOppositeDirectionOnSameInstrumentAndTimeframe() {
        Alert longAlert = new Alert("ema:golden:MCL:10m", AlertSeverity.INFO,
                "MCL [10m] - EMA Golden Cross", AlertCategory.EMA, "MCL");
        Alert shortAlert = new Alert("ema:death:MCL:10m", AlertSeverity.WARNING,
                "MCL [10m] - EMA Death Cross", AlertCategory.EMA, "MCL");

        service.recordSignals(List.of(longAlert), "10m");

        List<Alert> filtered = service.filter(List.of(shortAlert), "10m", "UNDEFINED");

        assertEquals(0, filtered.size());
    }

    @Test
    void antiChop_doesNotMixDifferentTimeframes() {
        Alert longAlert = new Alert("ema:golden:MCL:10m", AlertSeverity.INFO,
                "MCL [10m] - EMA Golden Cross", AlertCategory.EMA, "MCL");
        Alert shortAlert = new Alert("ema:death:MCL:1h", AlertSeverity.WARNING,
                "MCL [1h] - EMA Death Cross", AlertCategory.EMA, "MCL");

        service.recordSignals(List.of(longAlert), "10m");

        List<Alert> filtered = service.filter(List.of(shortAlert), "1h", "UNDEFINED");

        assertEquals(1, filtered.size());
    }
}
