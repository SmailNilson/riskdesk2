package com.riskdesk.domain.shared;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Resolves CME trading session boundaries.
 * <p>
 * CME Futures sessions run Sunday 17:00 ET through Friday 17:00 ET,
 * with a daily close/reopen at 17:00 ET (16:00 CT).  A "trading day"
 * is defined as the period from 17:00 ET on day N-1 to 17:00 ET on day N.
 * For example, the "Tuesday session" runs from Monday 17:00 ET to Tuesday 17:00 ET.
 * <p>
 * This class uses {@code ZoneId} so DST transitions (EST/EDT) are handled
 * automatically by the JDK.
 */
public final class TradingSessionResolver {

    public static final ZoneId CME_ZONE = ZoneId.of("America/New_York");
    public static final LocalTime CME_SESSION_CLOSE = LocalTime.of(17, 0);
    public static final LocalTime CME_SESSION_OPEN = LocalTime.of(18, 0);

    private TradingSessionResolver() {}

    /**
     * Returns {@code true} if the given timestamp falls within an active CME
     * futures trading session.
     * <p>
     * CME futures trade Sunday 18:00 ET through Friday 17:00 ET, with a daily
     * maintenance window from 17:00 ET to 18:00 ET (Monday–Thursday).
     * Weekend closure runs from Friday 17:00 ET to Sunday 18:00 ET.
     *
     * @return {@code true} if the market is open at the given instant
     */
    public static boolean isInTradingSession(Instant timestamp) {
        ZonedDateTime zdt = timestamp.atZone(CME_ZONE);
        DayOfWeek dow = zdt.getDayOfWeek();
        LocalTime time = zdt.toLocalTime();

        if (dow == DayOfWeek.SATURDAY) {
            return false;
        }
        if (dow == DayOfWeek.SUNDAY) {
            return !time.isBefore(CME_SESSION_OPEN);
        }
        if (dow == DayOfWeek.FRIDAY) {
            return time.isBefore(CME_SESSION_CLOSE);
        }
        // Monday–Thursday: closed during daily maintenance 17:00–18:00 ET
        return time.isBefore(CME_SESSION_CLOSE) || !time.isBefore(CME_SESSION_OPEN);
    }

    /**
     * Returns the start of the daily CME session containing the given timestamp.
     * <p>
     * If {@code timestamp} falls before 17:00 ET on its calendar day,
     * the session started at 17:00 ET the previous calendar day.
     * If it falls at or after 17:00 ET, the session started at 17:00 ET today.
     *
     * @return session start as UTC {@link Instant}
     */
    public static Instant dailySessionStart(Instant timestamp) {
        ZonedDateTime zdt = timestamp.atZone(CME_ZONE);
        ZonedDateTime sessionOpen;
        if (zdt.toLocalTime().isBefore(CME_SESSION_CLOSE)) {
            sessionOpen = zdt.toLocalDate().minusDays(1)
                    .atTime(CME_SESSION_CLOSE)
                    .atZone(CME_ZONE);
        } else {
            sessionOpen = zdt.toLocalDate()
                    .atTime(CME_SESSION_CLOSE)
                    .atZone(CME_ZONE);
        }
        return sessionOpen.toInstant();
    }

    /**
     * Returns the start of the weekly CME session containing the given timestamp.
     * The CME weekly session opens Sunday at 17:00 ET.
     *
     * @return weekly session start as UTC {@link Instant}
     */
    public static Instant weeklySessionStart(Instant timestamp) {
        ZonedDateTime zdt = timestamp.atZone(CME_ZONE);
        LocalDate date = zdt.toLocalDate();

        // Walk back to Sunday
        while (date.getDayOfWeek() != DayOfWeek.SUNDAY) {
            date = date.minusDays(1);
        }

        ZonedDateTime sundayOpen = date.atTime(CME_SESSION_CLOSE).atZone(CME_ZONE);

        // If the timestamp is on Sunday but before 17:00 ET, it belongs to the
        // previous week's session (which opened the prior Sunday at 17:00 ET).
        if (timestamp.isBefore(sundayOpen.toInstant())) {
            sundayOpen = sundayOpen.minusWeeks(1);
        }

        return sundayOpen.toInstant();
    }

    /**
     * Returns {@code true} if the given timestamp falls inside the CME daily
     * maintenance window (17:00–18:00 ET, Monday–Thursday) or during the
     * weekend closure (Friday 17:00 ET through Sunday 17:00 ET).
     * <p>
     * During these windows no trading occurs and price data is unreliable
     * (wide spreads, stale quotes).  Callers should skip candle accumulation
     * and treat streaming prices with caution.
     */
    public static boolean isMaintenanceWindow(Instant timestamp) {
        ZonedDateTime zdt = timestamp.atZone(CME_ZONE);
        DayOfWeek dow = zdt.getDayOfWeek();
        LocalTime time = zdt.toLocalTime();

        // Weekend closure: Friday 17:00 ET → Sunday 18:00 ET
        if (dow == DayOfWeek.SATURDAY) return true;
        if (dow == DayOfWeek.FRIDAY && !time.isBefore(CME_SESSION_CLOSE)) return true;
        if (dow == DayOfWeek.SUNDAY && time.isBefore(CME_SESSION_OPEN)) return true;

        // Daily maintenance halt: 17:00–18:00 ET (Mon–Thu)
        int hour = zdt.getHour();
        if (dow != DayOfWeek.FRIDAY && dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
            return hour == 17;
        }

        return false;
    }

    /**
     * Returns the CME trading date for a given timestamp.
     * <p>
     * The trading date is the calendar date on which the session <em>closes</em>.
     * A tick at 02:00 UTC Tuesday (21:00 ET Monday) belongs to the Tuesday session
     * (Monday 17:00 ET → Tuesday 17:00 ET), so its trading date is Tuesday.
     * A tick at 21:30 UTC Monday (16:30 ET Monday, before 17:00 ET) still belongs to
     * the Monday session, so its trading date is Monday.
     *
     * @return the trading date in the CME (America/New_York) calendar
     */
    public static LocalDate tradingDate(Instant timestamp) {
        ZonedDateTime zdt = timestamp.atZone(CME_ZONE);
        if (zdt.toLocalTime().isBefore(CME_SESSION_CLOSE)) {
            return zdt.toLocalDate();
        }
        return zdt.toLocalDate().plusDays(1);
    }
}
