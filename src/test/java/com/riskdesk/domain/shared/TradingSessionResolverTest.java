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

    // -- isInTradingSession ---------------------------------------------------

    @ParameterizedTest(name = "isInTradingSession({0}) = {1}")
    @CsvSource({
        // Monday mid-session
        "2026-03-23T14:00:00Z, true",   // Mon 10:00 ET (EDT) — mid-session
        // Monday daily maintenance window (17:00–18:00 ET)
        "2026-03-23T21:00:00Z, false",  // Mon 17:00 ET — maintenance starts
        "2026-03-23T21:30:00Z, false",  // Mon 17:30 ET — mid-maintenance
        "2026-03-23T22:00:00Z, true",   // Mon 18:00 ET — session reopens
        "2026-03-23T22:01:00Z, true",   // Mon 18:01 ET — clearly open
        // Friday close → weekend
        "2026-03-27T20:59:00Z, true",   // Fri 16:59 ET — just before close
        "2026-03-27T21:00:00Z, false",  // Fri 17:00 ET — weekend starts
        "2026-03-27T23:00:00Z, false",  // Fri 19:00 ET — weekend
        // Saturday — always closed
        "2026-03-28T12:00:00Z, false",  // Sat 08:00 ET
        "2026-03-28T22:00:00Z, false",  // Sat 18:00 ET
        // Sunday — closed until 18:00 ET
        "2026-03-29T17:00:00Z, false",  // Sun 13:00 ET — before open
        "2026-03-29T21:59:00Z, false",  // Sun 17:59 ET — just before open
        "2026-03-29T22:00:00Z, true",   // Sun 18:00 ET — weekly open
        "2026-03-29T23:00:00Z, true",   // Sun 19:00 ET — session active
    })
    void isInTradingSession_variousTimes(String utcTimestamp, boolean expected) {
        assertEquals(expected, TradingSessionResolver.isInTradingSession(Instant.parse(utcTimestamp)));
    }

    @Test
    void isInTradingSession_springForwardDST_handlesCorrectly() {
        // 2026 Spring Forward: March 8 at 02:00 EST -> 03:00 EDT
        // March 9 (Monday) maintenance: 17:00 EDT = 21:00 UTC
        Instant maintenance = ZonedDateTime.of(2026, 3, 9, 17, 30, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertFalse(TradingSessionResolver.isInTradingSession(maintenance));

        Instant afterMaint = ZonedDateTime.of(2026, 3, 9, 18, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertTrue(TradingSessionResolver.isInTradingSession(afterMaint));
    }

    @Test
    void isInTradingSession_fallBackDST_handlesCorrectly() {
        // 2026 Fall Back: Nov 1 at 02:00 EDT -> 01:00 EST
        // Nov 2 (Monday) maintenance: 17:00 EST = 22:00 UTC
        Instant maintenance = ZonedDateTime.of(2026, 11, 2, 17, 30, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertFalse(TradingSessionResolver.isInTradingSession(maintenance));

        Instant afterMaint = ZonedDateTime.of(2026, 11, 2, 18, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertTrue(TradingSessionResolver.isInTradingSession(afterMaint));
    }

    @Test
    void isInTradingSession_thursdayMaintenance_closedThenReopens() {
        // Thursday March 26, 2026
        Instant beforeClose = ZonedDateTime.of(2026, 3, 26, 16, 59, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertTrue(TradingSessionResolver.isInTradingSession(beforeClose));

        Instant atClose = ZonedDateTime.of(2026, 3, 26, 17, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertFalse(TradingSessionResolver.isInTradingSession(atClose));

        Instant atReopen = ZonedDateTime.of(2026, 3, 26, 18, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertTrue(TradingSessionResolver.isInTradingSession(atReopen));
    }

    // -- isMaintenanceWindow ---------------------------------------------------

    @Test
    void isMaintenanceWindow_duringDailyHalt_returnsTrue() {
        // Wednesday 2026-03-25 17:30 ET — inside the 17:00-18:00 ET maintenance halt
        Instant tick = ZonedDateTime.of(2026, 3, 25, 17, 30, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertTrue(TradingSessionResolver.isMaintenanceWindow(tick));
    }

    @Test
    void isMaintenanceWindow_justBeforeHalt_returnsFalse() {
        // Wednesday 2026-03-25 16:59 ET — just before the halt
        Instant tick = ZonedDateTime.of(2026, 3, 25, 16, 59, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertFalse(TradingSessionResolver.isMaintenanceWindow(tick));
    }

    @Test
    void isMaintenanceWindow_justAfterHalt_returnsFalse() {
        // Wednesday 2026-03-25 18:00 ET — session just reopened
        Instant tick = ZonedDateTime.of(2026, 3, 25, 18, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertFalse(TradingSessionResolver.isMaintenanceWindow(tick));
    }

    @Test
    void isMaintenanceWindow_saturday_returnsTrue() {
        // Saturday anytime
        Instant tick = ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertTrue(TradingSessionResolver.isMaintenanceWindow(tick));
    }

    @Test
    void isMaintenanceWindow_sundayBeforeOpen_returnsTrue() {
        // Sunday 2026-03-22 10:00 ET — before 17:00 ET open
        Instant tick = ZonedDateTime.of(2026, 3, 22, 10, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertTrue(TradingSessionResolver.isMaintenanceWindow(tick));
    }

    @Test
    void isMaintenanceWindow_sundayAfterOpen_returnsFalse() {
        // Sunday 2026-03-22 18:00 ET — market open, trading active
        Instant tick = ZonedDateTime.of(2026, 3, 22, 18, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertFalse(TradingSessionResolver.isMaintenanceWindow(tick));
    }

    @Test
    void isMaintenanceWindow_fridayAfterClose_returnsTrue() {
        // Friday 2026-03-27 17:30 ET — after Friday close at 17:00
        Instant tick = ZonedDateTime.of(2026, 3, 27, 17, 30, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertTrue(TradingSessionResolver.isMaintenanceWindow(tick));
    }

    @Test
    void isMaintenanceWindow_regularTradingHours_returnsFalse() {
        // Wednesday 2026-03-25 10:00 ET — regular session
        Instant tick = ZonedDateTime.of(2026, 3, 25, 10, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertFalse(TradingSessionResolver.isMaintenanceWindow(tick));
    }

    @Test
    void isMaintenanceWindow_nightSession_returnsFalse() {
        // Wednesday 2026-03-25 22:00 ET — electronic session overnight
        Instant tick = ZonedDateTime.of(2026, 3, 25, 22, 0, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        assertFalse(TradingSessionResolver.isMaintenanceWindow(tick));
    }
}
