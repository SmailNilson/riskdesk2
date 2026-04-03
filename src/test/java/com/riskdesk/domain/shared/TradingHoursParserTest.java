package com.riskdesk.domain.shared;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TradingHoursParserTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    // -- Basic parsing --------------------------------------------------------

    @Test
    void parse_sameDaySession_producesCorrectInterval() {
        // Thanksgiving Friday early close: 09:30 to 13:15 ET
        String raw = "20231124:0930-1315";
        List<TradingInterval> result = TradingHoursParser.parse(raw, "US/Eastern");

        assertEquals(1, result.size());

        Instant expectedOpen = ZonedDateTime.of(2023, 11, 24, 9, 30, 0, 0, ET).toInstant();
        Instant expectedClose = ZonedDateTime.of(2023, 11, 24, 13, 15, 0, 0, ET).toInstant();

        assertEquals(expectedOpen, result.get(0).open());
        assertEquals(expectedClose, result.get(0).close());
    }

    @Test
    void parse_overnightSession_closeIsNextDay() {
        // Typical CME futures: open 18:00, close 17:00 next day
        String raw = "20231127:1800-1700";
        List<TradingInterval> result = TradingHoursParser.parse(raw, "US/Eastern");

        assertEquals(1, result.size());

        Instant expectedOpen = ZonedDateTime.of(2023, 11, 27, 18, 0, 0, 0, ET).toInstant();
        Instant expectedClose = ZonedDateTime.of(2023, 11, 28, 17, 0, 0, 0, ET).toInstant();

        assertEquals(expectedOpen, result.get(0).open());
        assertEquals(expectedClose, result.get(0).close());
    }

    @Test
    void parse_closedDay_producesNoInterval() {
        String raw = "20231123:CLOSED";
        List<TradingInterval> result = TradingHoursParser.parse(raw, "US/Eastern");

        assertTrue(result.isEmpty());
    }

    // -- Multiple entries -----------------------------------------------------

    @Test
    void parse_multipleEntries_sortedByOpen() {
        String raw = "20231127:1800-1700;20231123:CLOSED;20231124:0930-1315";
        List<TradingInterval> result = TradingHoursParser.parse(raw, "US/Eastern");

        assertEquals(2, result.size());

        // First interval should be Nov 24 (early close), second Nov 27 (overnight)
        Instant nov24Open = ZonedDateTime.of(2023, 11, 24, 9, 30, 0, 0, ET).toInstant();
        assertEquals(nov24Open, result.get(0).open());

        Instant nov27Open = ZonedDateTime.of(2023, 11, 27, 18, 0, 0, 0, ET).toInstant();
        assertEquals(nov27Open, result.get(1).open());
    }

    // -- TradingInterval.contains() -------------------------------------------

    @Test
    void interval_contains_insideReturnsTrue() {
        Instant open = ZonedDateTime.of(2023, 11, 24, 9, 30, 0, 0, ET).toInstant();
        Instant close = ZonedDateTime.of(2023, 11, 24, 13, 15, 0, 0, ET).toInstant();
        TradingInterval interval = new TradingInterval(open, close);

        Instant mid = ZonedDateTime.of(2023, 11, 24, 11, 0, 0, 0, ET).toInstant();
        assertTrue(interval.contains(mid));
    }

    @Test
    void interval_contains_atOpenReturnsTrue() {
        Instant open = ZonedDateTime.of(2023, 11, 24, 9, 30, 0, 0, ET).toInstant();
        Instant close = ZonedDateTime.of(2023, 11, 24, 13, 15, 0, 0, ET).toInstant();
        TradingInterval interval = new TradingInterval(open, close);

        assertTrue(interval.contains(open));
    }

    @Test
    void interval_contains_atCloseReturnsFalse() {
        Instant open = ZonedDateTime.of(2023, 11, 24, 9, 30, 0, 0, ET).toInstant();
        Instant close = ZonedDateTime.of(2023, 11, 24, 13, 15, 0, 0, ET).toInstant();
        TradingInterval interval = new TradingInterval(open, close);

        assertFalse(interval.contains(close)); // close is exclusive
    }

    @Test
    void interval_contains_outsideReturnsFalse() {
        Instant open = ZonedDateTime.of(2023, 11, 24, 9, 30, 0, 0, ET).toInstant();
        Instant close = ZonedDateTime.of(2023, 11, 24, 13, 15, 0, 0, ET).toInstant();
        TradingInterval interval = new TradingInterval(open, close);

        Instant before = ZonedDateTime.of(2023, 11, 24, 8, 0, 0, 0, ET).toInstant();
        Instant after = ZonedDateTime.of(2023, 11, 24, 14, 0, 0, 0, ET).toInstant();
        assertFalse(interval.contains(before));
        assertFalse(interval.contains(after));
    }

    // -- Edge cases ------------------------------------------------------------

    @Test
    void parse_nullInput_returnsEmptyList() {
        assertTrue(TradingHoursParser.parse(null, "US/Eastern").isEmpty());
    }

    @Test
    void parse_blankInput_returnsEmptyList() {
        assertTrue(TradingHoursParser.parse("  ", "US/Eastern").isEmpty());
    }

    @Test
    void parse_malformedEntry_skipsGracefully() {
        String raw = "20231124:0930-1315;GARBAGE;20231127:1800-1700";
        List<TradingInterval> result = TradingHoursParser.parse(raw, "US/Eastern");

        assertEquals(2, result.size());
    }

    @Test
    void parse_nullTimezone_defaultsToCmeZone() {
        String raw = "20231124:0930-1315";
        List<TradingInterval> result = TradingHoursParser.parse(raw, null);

        assertEquals(1, result.size());
        // Should use America/New_York as default
        Instant expectedOpen = ZonedDateTime.of(2023, 11, 24, 9, 30, 0, 0, ET).toInstant();
        assertEquals(expectedOpen, result.get(0).open());
    }

    // -- Timezone resolution --------------------------------------------------

    @Test
    void resolveZone_ibkrAlias_mapsCorrectly() {
        assertEquals(ZoneId.of("America/New_York"), TradingHoursParser.resolveZone("US/Eastern"));
        assertEquals(ZoneId.of("America/Chicago"), TradingHoursParser.resolveZone("US/Central"));
    }

    @Test
    void resolveZone_standardJavaZoneId_passesThrough() {
        assertEquals(ZoneId.of("America/New_York"), TradingHoursParser.resolveZone("America/New_York"));
        assertEquals(ZoneId.of("Europe/London"), TradingHoursParser.resolveZone("Europe/London"));
    }

    @Test
    void resolveZone_unknown_fallsToCmeZone() {
        assertEquals(TradingSessionResolver.CME_ZONE, TradingHoursParser.resolveZone("Invalid/Zone"));
    }

    // -- Midnight-to-midnight (24h) -------------------------------------------

    @Test
    void parse_midnightToMidnight_wrapsToNextDay() {
        String raw = "20231127:0000-0000";
        List<TradingInterval> result = TradingHoursParser.parse(raw, "US/Eastern");

        assertEquals(1, result.size());
        Instant expectedOpen = ZonedDateTime.of(2023, 11, 27, 0, 0, 0, 0, ET).toInstant();
        Instant expectedClose = ZonedDateTime.of(2023, 11, 28, 0, 0, 0, 0, ET).toInstant();

        assertEquals(expectedOpen, result.get(0).open());
        assertEquals(expectedClose, result.get(0).close());
    }
}
