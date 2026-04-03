package com.riskdesk.domain.shared;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Parses the IBKR {@code tradingHours} (or {@code liquidHours}) string returned
 * by {@code reqContractDetails} into a sorted list of {@link TradingInterval}s.
 *
 * <h3>IBKR format</h3>
 * Semicolon-separated day entries, each in the form
 * {@code YYYYMMDD:HHMM-HHMM} or {@code YYYYMMDD:CLOSED}.
 * <ul>
 *   <li>{@code 20231123:CLOSED} — market closed all day</li>
 *   <li>{@code 20231124:0930-1600} — same-day session</li>
 *   <li>{@code 20231127:1800-1700} — overnight session (open &gt; close → close
 *       is on the following calendar day)</li>
 * </ul>
 *
 * <p>All times are expressed in the exchange's local timezone, provided by
 * {@code ContractDetails.timeZoneId()}.  This parser converts them to absolute
 * {@link Instant}s so downstream code is timezone-agnostic.
 *
 * <p>This class is pure domain logic — no Spring dependencies.
 */
public final class TradingHoursParser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Common IBKR timezone strings that don't match {@link ZoneId#of} directly. */
    private static final Map<String, String> TZ_ALIASES = Map.of(
            "US/Eastern",  "America/New_York",
            "US/Central",  "America/Chicago",
            "US/Pacific",  "America/Los_Angeles",
            "US/Mountain", "America/Denver",
            "EST",         "America/New_York",
            "CST",         "America/Chicago",
            "MET",         "Europe/Berlin",
            "Japan",       "Asia/Tokyo"
    );

    private TradingHoursParser() {}

    /**
     * Parses an IBKR {@code tradingHours} string into a sorted list of
     * {@link TradingInterval}s.
     *
     * @param tradingHours raw string from {@code ContractDetails.tradingHours()}
     * @param ibkrTimezone timezone string from {@code ContractDetails.timeZoneId()}
     *                     (e.g. {@code "US/Eastern"})
     * @return sorted intervals (oldest first); empty list on null/blank input
     */
    public static List<TradingInterval> parse(String tradingHours, String ibkrTimezone) {
        if (tradingHours == null || tradingHours.isBlank()) {
            return List.of();
        }

        ZoneId zone = resolveZone(ibkrTimezone);
        List<TradingInterval> intervals = new ArrayList<>();

        for (String entry : tradingHours.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;

            TradingInterval interval = parseEntry(trimmed, zone);
            if (interval != null) {
                intervals.add(interval);
            }
        }

        intervals.sort(Comparator.comparing(TradingInterval::open));
        return List.copyOf(intervals);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Parses one entry, e.g. {@code "20231124:0930-1600"} or {@code "20231124:CLOSED"}.
     * Returns {@code null} for CLOSED days or unparseable entries.
     */
    private static TradingInterval parseEntry(String entry, ZoneId zone) {
        int colonIdx = entry.indexOf(':');
        if (colonIdx < 0) return null;

        String datePart = entry.substring(0, colonIdx).trim();
        String timePart = entry.substring(colonIdx + 1).trim();

        if ("CLOSED".equalsIgnoreCase(timePart)) {
            return null;
        }

        LocalDate date;
        try {
            date = LocalDate.parse(datePart, DATE_FMT);
        } catch (Exception e) {
            return null; // skip malformed entries
        }

        int dashIdx = timePart.indexOf('-');
        if (dashIdx < 0) return null;

        LocalTime openTime = parseTime(timePart.substring(0, dashIdx).trim());
        LocalTime closeTime = parseTime(timePart.substring(dashIdx + 1).trim());
        if (openTime == null || closeTime == null) return null;

        ZonedDateTime openZdt = date.atTime(openTime).atZone(zone);
        ZonedDateTime closeZdt;

        if (closeTime.isAfter(openTime) || closeTime.equals(openTime)) {
            // Same-day session: e.g. 0930-1600
            closeZdt = date.atTime(closeTime).atZone(zone);
        } else {
            // Overnight session: e.g. 1800-1700 → close is on the next calendar day
            closeZdt = date.plusDays(1).atTime(closeTime).atZone(zone);
        }

        // Special case: 0000-0000 means 24h (midnight to midnight next day)
        if (openTime.equals(LocalTime.MIDNIGHT) && closeTime.equals(LocalTime.MIDNIGHT)) {
            closeZdt = date.plusDays(1).atTime(LocalTime.MIDNIGHT).atZone(zone);
        }

        return new TradingInterval(openZdt.toInstant(), closeZdt.toInstant());
    }

    /** Parses {@code "HHMM"} into a {@link LocalTime}. */
    private static LocalTime parseTime(String hhmm) {
        if (hhmm.length() != 4) return null;
        try {
            int h = Integer.parseInt(hhmm.substring(0, 2));
            int m = Integer.parseInt(hhmm.substring(2, 4));
            return LocalTime.of(h, m);
        } catch (Exception e) {
            return null;
        }
    }

    /** Resolves IBKR timezone strings to Java {@link ZoneId}. */
    static ZoneId resolveZone(String ibkrTimezone) {
        if (ibkrTimezone == null || ibkrTimezone.isBlank()) {
            return TradingSessionResolver.CME_ZONE; // safe default
        }
        String mapped = TZ_ALIASES.get(ibkrTimezone.trim());
        if (mapped != null) {
            return ZoneId.of(mapped);
        }
        try {
            return ZoneId.of(ibkrTimezone.trim());
        } catch (Exception e) {
            return TradingSessionResolver.CME_ZONE; // fallback
        }
    }
}
