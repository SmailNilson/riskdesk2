package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import com.riskdesk.domain.calendar.NewsBlackoutCalendar;
import com.riskdesk.domain.calendar.NewsEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
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

    // ── Rule 6 : News Blackout ──────────────────────────────────────────────

    @Test
    void newsBlackout_disabledCalendar_passesSignalsThrough() {
        SignalPreFilterService filterWithDisabledCalendar =
                new SignalPreFilterService(NewsBlackoutCalendar.disabled());
        Alert longAlert = new Alert("wt:bullish:MCL:10m", AlertSeverity.WARNING,
                "MCL [10m] - WaveTrend Bullish Cross", AlertCategory.WAVETREND, "MCL");

        List<Alert> filtered = filterWithDisabledCalendar.filter(List.of(longAlert), "10m", "UNDEFINED");

        assertEquals(1, filtered.size(), "Disabled calendar must never block signals");
    }

    @Test
    void newsBlackout_nullCalendarDefaultsToDisabled_passesSignalsThrough() {
        SignalPreFilterService filterWithNullCalendar = new SignalPreFilterService(null);
        Alert longAlert = new Alert("wt:bullish:MCL:10m", AlertSeverity.WARNING,
                "MCL [10m] - WaveTrend Bullish Cross", AlertCategory.WAVETREND, "MCL");

        List<Alert> filtered = filterWithNullCalendar.filter(List.of(longAlert), "10m", "UNDEFINED");

        assertEquals(1, filtered.size(),
                "Null calendar must degrade gracefully to a disabled calendar, not NPE");
    }

    @Test
    void newsBlackout_activeWindow_blocksAllSignalsIncludingH1() {
        // Build a calendar whose window contains *now* so the prefilter trips.
        NewsEvent fomcNow = new NewsEvent(Instant.now(), "FOMC Rate Decision", NewsEvent.Impact.HIGH);
        NewsBlackoutCalendar active = new NewsBlackoutCalendar(
                true, List.of(fomcNow), Duration.ofMinutes(30), Duration.ofMinutes(30));
        SignalPreFilterService filter = new SignalPreFilterService(active);

        Alert h1Alert = new Alert("smc:bullish:MNQ:1h", AlertSeverity.WARNING,
                "MNQ [1h] - SMC BULLISH CHoCH", AlertCategory.SMC, "MNQ");
        Alert ltfAlert = new Alert("wt:bullish:MCL:10m", AlertSeverity.WARNING,
                "MCL [10m] - WaveTrend Bullish Cross", AlertCategory.WAVETREND, "MCL");

        assertEquals(0, filter.filter(List.of(h1Alert), "1h", "UNDEFINED").size(),
                "News blackout must block H1 structural signals — FOMC crushes everything");
        assertEquals(0, filter.filter(List.of(ltfAlert), "10m", "UNDEFINED").size(),
                "News blackout must block LTF signals");
    }

    @Test
    void newsBlackout_eventOutsideWindow_doesNotBlockSignals() {
        // Event far in the future → window is nowhere near now → signals pass.
        NewsEvent future = new NewsEvent(
                Instant.now().plus(Duration.ofDays(7)),
                "Future FOMC",
                NewsEvent.Impact.HIGH);
        NewsBlackoutCalendar inactive = new NewsBlackoutCalendar(
                true, List.of(future), Duration.ofMinutes(30), Duration.ofMinutes(15));
        SignalPreFilterService filter = new SignalPreFilterService(inactive);

        Alert longAlert = new Alert("wt:bullish:MCL:10m", AlertSeverity.WARNING,
                "MCL [10m] - WaveTrend Bullish Cross", AlertCategory.WAVETREND, "MCL");

        List<Alert> filtered = filter.filter(List.of(longAlert), "10m", "UNDEFINED");

        assertEquals(1, filtered.size(),
                "Events outside the blackout window must not suppress signals");
    }

    @Test
    void newsBlackout_short_circuitsBeforeHtfAndAntiChop() {
        // Rule 6 is evaluated first — the only signal configured would also be blocked
        // by Rule 1 (BULLISH H1 blocks SHORT); verify Rule 6 reason wins by checking
        // behaviour: even a signal that Rule 1 would allow gets blocked by news.
        NewsEvent nowEvent = new NewsEvent(Instant.now(), "CPI Release", NewsEvent.Impact.HIGH);
        NewsBlackoutCalendar active = new NewsBlackoutCalendar(
                true, List.of(nowEvent), Duration.ofMinutes(10), Duration.ofMinutes(10));
        SignalPreFilterService filter = new SignalPreFilterService(active);

        // Trend-aligned LONG on 10m — Rule 1 would pass this easily.
        Alert alignedLong = new Alert("wt:bullish:MGC:10m", AlertSeverity.WARNING,
                "MGC [10m] - WaveTrend Bullish Cross", AlertCategory.WAVETREND, "MGC");

        List<Alert> filtered = filter.filter(List.of(alignedLong), "10m", "BULLISH");

        assertEquals(0, filtered.size(),
                "News blackout must fire even when every other rule would allow the signal");
    }
}
