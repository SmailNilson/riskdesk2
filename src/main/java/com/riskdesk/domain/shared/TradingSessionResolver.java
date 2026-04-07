package com.riskdesk.domain.shared;

import com.riskdesk.domain.model.Instrument;

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

    /**
     * Daily CME Globex maintenance windows (America/New_York, DST-aware).
     * <ul>
     *   <li>Standard futures (energy MCL, metals MGC, equity-index MNQ): 17:00–18:00 ET</li>
     *   <li>FX futures (6E / Euro FX): 16:00–17:00 ET</li>
     * </ul>
     * During these windows IBKR may emit stale/delayed ticks for instruments whose
     * exchange is still closed. Candle accumulation and indicator evaluation should
     * be suppressed. Use the caller's knowledge of instrument type to choose the right method.
     */
    private static final LocalTime STANDARD_MAINTENANCE_END = LocalTime.of(18, 0);
    private static final LocalTime FX_MAINTENANCE_START     = LocalTime.of(16, 0);

    private TradingSessionResolver() {}

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
     * Returns {@code true} if the CME futures market is open at the given timestamp.
     * <p>
     * CME weekly session: Sunday 17:00 ET → Friday 17:00 ET.
     * Outside this window (Friday 17:00 ET → Sunday 17:00 ET) the market is closed.
     * This does NOT filter the daily 17:00–18:00 ET maintenance halt because
     * IBKR data is already clean and indicators need contiguous series.
     */
    public static boolean isMarketOpen(Instant timestamp) {
        ZonedDateTime zdt = timestamp.atZone(CME_ZONE);
        DayOfWeek dow = zdt.getDayOfWeek();
        LocalTime time = zdt.toLocalTime();

        // Saturday: always closed
        if (dow == DayOfWeek.SATURDAY) return false;
        // Sunday before 17:00 ET: closed
        if (dow == DayOfWeek.SUNDAY && time.isBefore(CME_SESSION_CLOSE)) return false;
        // Friday at or after 17:00 ET: closed
        if (dow == DayOfWeek.FRIDAY && !time.isBefore(CME_SESSION_CLOSE)) return false;

        return true;
    }

    /**
     * Instrument-aware overload for future extensibility.
     * <p>
     * Currently all exchange-traded instruments (MCL, MGC, E6, MNQ) follow
     * the same CME Globex schedule.  Synthetic instruments (DXY) are excluded
     * from session filtering because they derive from FX pairs that trade 24/5.
     *
     * @param timestamp  the moment to check
     * @param instrument the instrument to check
     * @return true if the market is open for this instrument at that instant
     */
    public static boolean isMarketOpen(Instant timestamp, Instrument instrument) {
        if (instrument.isSynthetic()) return true;
        return isMarketOpen(timestamp);
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

    /**
     * Returns {@code true} if the timestamp falls in the standard CME Globex daily
     * maintenance window: <b>17:00–18:00 ET</b> (DST-aware).
     * <p>
     * Applies to: MCL (energy), MGC (metals), MNQ (equity index).
     * During this window IBKR may forward stale last-trade ticks even though the
     * exchange is halted. Callers should suppress candle accumulation and alerts.
     */
    public static boolean isStandardMaintenanceWindow(Instant timestamp) {
        LocalTime t = timestamp.atZone(CME_ZONE).toLocalTime();
        return !t.isBefore(CME_SESSION_CLOSE) && t.isBefore(STANDARD_MAINTENANCE_END);
    }

    /**
     * Returns {@code true} if the timestamp falls in the FX CME Globex daily
     * maintenance window: <b>16:00–17:00 ET</b> (DST-aware).
     * <p>
     * Applies to: E6 (Euro FX Futures) and other CME FX instruments.
     */
    public static boolean isFxMaintenanceWindow(Instant timestamp) {
        LocalTime t = timestamp.atZone(CME_ZONE).toLocalTime();
        return !t.isBefore(FX_MAINTENANCE_START) && t.isBefore(CME_SESSION_CLOSE);
    }

    /**
     * Returns {@code true} if the timestamp falls within an ICT kill zone:
     * London 02:00–05:00 ET, NY 08:30–11:00 ET.
     * Used to gate 5m alert evaluation.
     */
    public static boolean isWithinKillZone(Instant timestamp) {
        LocalTime t = timestamp.atZone(CME_ZONE).toLocalTime();
        return (t.compareTo(LocalTime.of(2, 0)) >= 0 && t.isBefore(LocalTime.of(5, 0)))
            || (t.compareTo(LocalTime.of(8, 30)) >= 0 && t.isBefore(LocalTime.of(11, 0)));
    }
}
