package com.riskdesk.domain.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class TradingSessionResolverTest {

    // -- dailySessionStart ---------------------------------------------------

    @Test
    void dailySessionStart_beforeCloseET_returnsPreviousDayClose() {
        // 2026-03-25 14:00 ET = 18:00 UTC (before 17:00 ET)
        Instant tick = ZonedDateTime.of(2026, 3, 25, 14, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();

        Instant sessionStart = TradingSessionResolver.dailySessionStart(tick);

        // Should be 2026-03-24 17:00 ET = 21:00 UTC (EDT in March)
        Instant expected = ZonedDateTime.of(2026, 3, 24, 17, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertEquals(expected, sessionStart);
    }

    @Test
    void dailySessionStart_afterCloseET_returnsSameDayClose() {
        // 2026-03-25 18:30 ET (after 17:00 ET)
        Instant tick = ZonedDateTime.of(2026, 3, 25, 18, 30, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();

        Instant sessionStart = TradingSessionResolver.dailySessionStart(tick);

        // Should be 2026-03-25 17:00 ET
        Instant expected = ZonedDateTime.of(2026, 3, 25, 17, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertEquals(expected, sessionStart);
    }

    @Test
    void dailySessionStart_exactlyAtClose_returnsSameDayClose() {
        // Exactly 17:00 ET — belongs to the new session starting at that moment
        Instant tick = ZonedDateTime.of(2026, 3, 25, 17, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();

        Instant sessionStart = TradingSessionResolver.dailySessionStart(tick);

        Instant expected = ZonedDateTime.of(2026, 3, 25, 17, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertEquals(expected, sessionStart);
    }

    // -- DST transitions -----------------------------------------------------

    @Test
    void dailySessionStart_springForwardDST_handlesCorrectly() {
        // 2026 Spring Forward: March 8 at 02:00 EST -> 03:00 EDT
        // A tick on March 9 at 10:00 EDT (before 17:00 EDT)
        Instant tick = ZonedDateTime.of(2026, 3, 9, 10, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();

        Instant sessionStart = TradingSessionResolver.dailySessionStart(tick);

        // Session started March 8 at 17:00 EDT (after spring forward happened)
        Instant expected = ZonedDateTime.of(2026, 3, 8, 17, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertEquals(expected, sessionStart);
    }

    @Test
    void dailySessionStart_fallBackDST_handlesCorrectly() {
        // 2026 Fall Back: Nov 1 at 02:00 EDT -> 01:00 EST
        // A tick on Nov 2 at 10:00 EST (before 17:00 EST)
        Instant tick = ZonedDateTime.of(2026, 11, 2, 10, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();

        Instant sessionStart = TradingSessionResolver.dailySessionStart(tick);

        // Session started Nov 1 at 17:00 EST (after fall back)
        Instant expected = ZonedDateTime.of(2026, 11, 1, 17, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertEquals(expected, sessionStart);
    }

    // -- UTC offset verification ---------------------------------------------

    @Test
    void dailySessionStart_winterTime_17hET_is_22hUTC() {
        // In EST (winter): 17:00 ET = 22:00 UTC
        Instant tick = ZonedDateTime.of(2026, 1, 15, 10, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();

        Instant sessionStart = TradingSessionResolver.dailySessionStart(tick);

        // January 14 at 17:00 EST = 22:00 UTC
        assertEquals(LocalDateTime.of(2026, 1, 14, 22, 0).toInstant(ZoneOffset.UTC),
                sessionStart);
    }

    @Test
    void dailySessionStart_summerTime_17hET_is_21hUTC() {
        // In EDT (summer): 17:00 ET = 21:00 UTC
        Instant tick = ZonedDateTime.of(2026, 7, 15, 10, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();

        Instant sessionStart = TradingSessionResolver.dailySessionStart(tick);

        // July 14 at 17:00 EDT = 21:00 UTC
        assertEquals(LocalDateTime.of(2026, 7, 14, 21, 0).toInstant(ZoneOffset.UTC),
                sessionStart);
    }

    // -- weeklySessionStart --------------------------------------------------

    @Test
    void weeklySessionStart_midweek_returnsSunday17hET() {
        // Wednesday March 25, 2026 at 14:00 ET
        Instant tick = ZonedDateTime.of(2026, 3, 25, 14, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();

        Instant weekStart = TradingSessionResolver.weeklySessionStart(tick);

        // Sunday March 22 at 17:00 EDT
        Instant expected = ZonedDateTime.of(2026, 3, 22, 17, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertEquals(expected, weekStart);
    }

    @Test
    void weeklySessionStart_sundayBeforeOpen_returnsPreviousWeek() {
        // Sunday March 22, 2026 at 10:00 ET (before 17:00 ET open)
        Instant tick = ZonedDateTime.of(2026, 3, 22, 10, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();

        Instant weekStart = TradingSessionResolver.weeklySessionStart(tick);

        // Should be previous Sunday March 15 at 17:00 EDT
        Instant expected = ZonedDateTime.of(2026, 3, 15, 17, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertEquals(expected, weekStart);
    }

    @Test
    void weeklySessionStart_sundayAfterOpen_returnsThisSunday() {
        // Sunday March 22, 2026 at 18:00 ET (after 17:00 ET open)
        Instant tick = ZonedDateTime.of(2026, 3, 22, 18, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();

        Instant weekStart = TradingSessionResolver.weeklySessionStart(tick);

        // Should be this Sunday March 22 at 17:00 EDT
        Instant expected = ZonedDateTime.of(2026, 3, 22, 17, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertEquals(expected, weekStart);
    }

    // -- tradingDate ---------------------------------------------------------

    @Test
    void tradingDate_beforeCloseET_returnsSameCalendarDate() {
        // 2026-03-25 14:00 ET (before 17:00 ET close)
        Instant tick = ZonedDateTime.of(2026, 3, 25, 14, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();

        LocalDate date = TradingSessionResolver.tradingDate(tick);

        // Still in the March 25 session, trading date = March 25
        assertEquals(LocalDate.of(2026, 3, 25), date);
    }

    @Test
    void tradingDate_afterCloseET_returnsNextCalendarDate() {
        // 2026-03-25 18:00 ET (after 17:00 ET close = new session)
        Instant tick = ZonedDateTime.of(2026, 3, 25, 18, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();

        LocalDate date = TradingSessionResolver.tradingDate(tick);

        // New session opened, trading date = March 26
        assertEquals(LocalDate.of(2026, 3, 26), date);
    }

    @ParameterizedTest
    @CsvSource({
        // UTC timestamp, expected trading date
        // 2026-03-25 03:00 UTC = 2026-03-24 23:00 ET (after 17:00 ET) -> trading date = March 25
        "2026-03-25T03:00:00Z, 2026-03-25",
        // 2026-03-25 20:00 UTC = 2026-03-25 16:00 ET (before 17:00 ET) -> trading date = March 25
        "2026-03-25T20:00:00Z, 2026-03-25",
        // 2026-03-25 22:00 UTC = 2026-03-25 18:00 ET (after 17:00 ET) -> trading date = March 26
        "2026-03-25T22:00:00Z, 2026-03-26",
    })
    void tradingDate_variousUTCTimes(String utcTimestamp, String expectedDate) {
        Instant tick = Instant.parse(utcTimestamp);
        assertEquals(LocalDate.parse(expectedDate), TradingSessionResolver.tradingDate(tick));
    }
}
