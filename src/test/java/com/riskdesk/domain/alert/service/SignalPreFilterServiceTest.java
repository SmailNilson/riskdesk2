package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignalPreFilterServiceTest {

    private final SignalPreFilterService service = new SignalPreFilterService();

    @Test
    void htfTrendFilter_bullishBlocksShortOnLtf() {
        Alert shortAlert = new Alert("wt:bearish:E6:10m", AlertSeverity.WARNING,
                "E6 [10m] - WaveTrend Bearish Cross", AlertCategory.WAVETREND, "E6");

        List<Alert> filtered = service.filter(List.of(shortAlert), "10m", "BULLISH");

        assertEquals(0, filtered.size(), "BULLISH H1 trend should block SHORT signals on 10m");
    }

    @Test
    void htfTrendFilter_bearishBlocksLongOnLtf() {
        Alert longAlert = new Alert("wt:bullish:MCL:10m", AlertSeverity.WARNING,
                "MCL [10m] - WaveTrend Bullish Cross", AlertCategory.WAVETREND, "MCL");

        List<Alert> filtered = service.filter(List.of(longAlert), "10m", "BEARISH");

        assertEquals(0, filtered.size(), "BEARISH H1 trend should block LONG signals on 10m");
    }

    @Test
    void htfTrendFilter_undefinedAllowsBothDirections() {
        Alert longAlert = new Alert("wt:bullish:MCL:10m", AlertSeverity.WARNING,
                "MCL [10m] - WaveTrend Bullish Cross", AlertCategory.WAVETREND, "MCL");
        Alert shortAlert = new Alert("wt:bearish:MCL:10m", AlertSeverity.WARNING,
                "MCL [10m] - WaveTrend Bearish Cross", AlertCategory.WAVETREND, "MCL");

        // No chop registration — test HTF filter only
        List<Alert> filteredLong = service.filter(List.of(longAlert), "10m", "UNDEFINED");
        assertEquals(1, filteredLong.size(), "UNDEFINED trend should allow LONG");

        SignalPreFilterService service2 = new SignalPreFilterService();
        List<Alert> filteredShort = service2.filter(List.of(shortAlert), "10m", "UNDEFINED");
        assertEquals(1, filteredShort.size(), "UNDEFINED trend should allow SHORT");
    }

    @Test
    void htfTrendFilter_trendAlignedSignalAllowed() {
        Alert longAlert = new Alert("wt:bullish:MGC:10m", AlertSeverity.WARNING,
                "MGC [10m] - WaveTrend Bullish Cross", AlertCategory.WAVETREND, "MGC");

        List<Alert> filtered = service.filter(List.of(longAlert), "10m", "BULLISH");

        assertEquals(1, filtered.size(), "BULLISH H1 trend should allow LONG signals");
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

    // ── PR-11 · Rule 5 regime-aware noise suppression ───────────────────────
    // CHAIKIN and DELTA_FLOW live in family "Flow" — CMF zero-line crosses and
    // DELTA_FLOW bias reversals whip back and forth in chop. They must be
    // suppressed on LTF when regime=CHOPPY. Real-tick ABSORPTION and
    // DELTA_OSCILLATOR (family "OrderFlow") still pass — different family.

    @Test
    void rule5_chaikinBlockedInChoppyRegime_onLtf() {
        Alert cmf = new Alert("cmf:bullish:MCL:10m", AlertSeverity.INFO,
                "MCL [10m] - Chaikin Bullish Cross", AlertCategory.CHAIKIN, "MCL");

        List<Alert> filtered = service.filter(List.of(cmf), "10m", "UNDEFINED", "CHOPPY");

        assertEquals(0, filtered.size(),
                "CHAIKIN (Flow family) must be suppressed in CHOPPY regime on LTF");
    }

    @Test
    void rule5_deltaFlowBlockedInChoppyRegime_onLtf() {
        Alert delta = new Alert("delta:buy:MCL:10m", AlertSeverity.INFO,
                "MCL [10m] - Delta Flow BUYING", AlertCategory.DELTA_FLOW, "MCL");

        List<Alert> filtered = service.filter(List.of(delta), "10m", "UNDEFINED", "CHOPPY");

        assertEquals(0, filtered.size(),
                "DELTA_FLOW (Flow family) must be suppressed in CHOPPY regime on LTF");
    }

    @Test
    void rule5_chaikinAllowedInTrendingRegime() {
        Alert cmf = new Alert("cmf:bullish:MCL:10m", AlertSeverity.INFO,
                "MCL [10m] - Chaikin Bullish Cross", AlertCategory.CHAIKIN, "MCL");

        List<Alert> filtered = service.filter(List.of(cmf), "10m", "UNDEFINED", "TRENDING_UP");

        assertEquals(1, filtered.size(),
                "CHAIKIN in a trending regime confirms the trend — must pass");
    }

    @Test
    void rule5_chaikinAllowedInRangingRegime() {
        // RANGING is deliberately distinct from CHOPPY — most valid reversal
        // trades form at range edges where CMF/DELTA are meaningful.
        Alert cmf = new Alert("cmf:bullish:MCL:10m", AlertSeverity.INFO,
                "MCL [10m] - Chaikin Bullish Cross", AlertCategory.CHAIKIN, "MCL");

        List<Alert> filtered = service.filter(List.of(cmf), "10m", "UNDEFINED", "RANGING");

        assertEquals(1, filtered.size(),
                "CHAIKIN must pass in RANGING regime — only CHOPPY suppresses Flow signals");
    }

    @Test
    void rule5_nullRegime_doesNotBlockAnything() {
        // Regime detection may be offline — signals must not be silently dropped.
        Alert cmf = new Alert("cmf:bullish:MCL:10m", AlertSeverity.INFO,
                "MCL [10m] - Chaikin Bullish Cross", AlertCategory.CHAIKIN, "MCL");

        List<Alert> filtered = service.filter(List.of(cmf), "10m", "UNDEFINED", null);

        assertEquals(1, filtered.size(),
                "Missing regime input must fail-open — never silently suppress Flow signals");
    }

    @Test
    void rule5_chaikinAllowedOn1h_regardlessOfRegime() {
        // H1 is structural timeframe — Rule 5 only applies on LTF.
        Alert cmf = new Alert("cmf:bullish:MCL:1h", AlertSeverity.INFO,
                "MCL [1h] - Chaikin Bullish Cross", AlertCategory.CHAIKIN, "MCL");

        List<Alert> filtered = service.filter(List.of(cmf), "1h", "UNDEFINED", "CHOPPY");

        assertEquals(1, filtered.size(),
                "H1 CHAIKIN must pass even in CHOPPY regime — Rule 5 is LTF-only");
    }

    @Test
    void rule5_absorptionStillAllowedInChoppyRegime_differentFamily() {
        // ABSORPTION is "OrderFlow" family — real tick flow, not a derived oscillator.
        // It MUST keep firing in chop because institutional absorption often precedes
        // the breakout that ends the chop.
        Alert absorption = new Alert("absorption:bullish:MCL:10m", AlertSeverity.INFO,
                "MCL [10m] - Bullish Absorption detected", AlertCategory.ABSORPTION, "MCL");

        List<Alert> filtered = service.filter(List.of(absorption), "10m", "UNDEFINED", "CHOPPY");

        assertEquals(1, filtered.size(),
                "ABSORPTION (OrderFlow family) must NOT be suppressed in CHOPPY — real tick flow is high-signal in chop");
    }

    @Test
    void rule5_structuralSignalsStillPassInChoppyRegime() {
        // SMC / Order Block signals are structural — they always pass, regardless of regime.
        Alert bos = new Alert("smc:bos:MCL:10m", AlertSeverity.WARNING,
                "MCL [10m] - SMC BULLISH BOS", AlertCategory.SMC, "MCL");

        List<Alert> filtered = service.filter(List.of(bos), "10m", "UNDEFINED", "CHOPPY");

        assertEquals(1, filtered.size(),
                "Structural SMC signals must always pass — regime gate does not apply to structure");
    }
}
