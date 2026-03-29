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
