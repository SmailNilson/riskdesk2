package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.engine.indicators.SessionVolumeProfileCalculator.NakedPoc;
import com.riskdesk.domain.engine.indicators.SessionVolumeProfileCalculator.PriceRange;
import com.riskdesk.domain.engine.indicators.SessionVolumeProfileCalculator.SessionPocRange;
import com.riskdesk.domain.engine.indicators.SessionVolumeProfileCalculator.SessionProfile;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionVolumeProfileCalculatorTest {

    private static final BigDecimal ONE_POINT = BigDecimal.ONE;

    private final SessionVolumeProfileCalculator calculator = new SessionVolumeProfileCalculator();

    /** Fixed instant inside an RTH session — 2026-04-15 14:00:00Z = 10:00 ET (EDT). */
    private static final Instant T0 = Instant.parse("2026-04-15T14:00:00Z");

    private static Candle candle(Instant ts, double open, double high, double low, double close, long volume) {
        return new Candle(Instrument.MNQ, "1m", ts,
            BigDecimal.valueOf(open), BigDecimal.valueOf(high),
            BigDecimal.valueOf(low), BigDecimal.valueOf(close), volume);
    }

    // ─── Range-distributed binning ─────────────────────────────────────────────

    @Test
    void candleInsideOneBucket_putsAllVolumeThere() {
        // [21000.25, 21000.75] sits entirely inside the 21000 bucket
        List<Candle> candles = List.of(
            candle(T0, 21000.5, 21000.75, 21000.25, 21000.5, 100));

        SessionProfile profile = calculator.compute(candles, ONE_POINT);

        assertNotNull(profile);
        assertEquals(0, profile.poc().compareTo(BigDecimal.valueOf(21000)));
        assertEquals(100, profile.totalVolume());
        // single-bucket profile: VA collapses onto the POC bucket
        assertEquals(0, profile.vah().compareTo(BigDecimal.valueOf(21000)));
        assertEquals(0, profile.val().compareTo(BigDecimal.valueOf(21000)));
    }

    @Test
    void candleSpanningBuckets_distributesVolumeProportionally() {
        // Range [21000, 21004): spans buckets 21000..21003 evenly (4 points), 400 contracts.
        // A second candle adds 200 fully inside the 21001 bucket -> POC = 21001.
        List<Candle> candles = List.of(
            candle(T0, 21000, 21004, 21000, 21002, 400),
            candle(T0.plusSeconds(60), 21001.25, 21001.75, 21001.25, 21001.5, 200));

        SessionProfile profile = calculator.compute(candles, ONE_POINT);

        assertNotNull(profile);
        // 21001 holds ~100 (spread) + 200 (inside) = 300, every other bucket ~100
        assertEquals(0, profile.poc().compareTo(BigDecimal.valueOf(21001)));
        assertEquals(600, profile.totalVolume());
    }

    @Test
    void typicalPriceBiasIsGone_flatRangeCandleDoesNotPileOnOneBucket() {
        // One wide candle [21000, 21010] with 1000 contracts: the legacy typical-price
        // binning would drop all 1000 on a single bucket; range distribution spreads it.
        List<Candle> candles = List.of(
            candle(T0, 21005, 21010, 21000, 21005, 1000),
            candle(T0.plusSeconds(60), 21002.25, 21002.75, 21002.25, 21002.5, 50));

        SessionProfile profile = calculator.compute(candles, ONE_POINT);

        assertNotNull(profile);
        // 21002 = 100 (share of the wide candle) + 50 = 150 -> POC
        assertEquals(0, profile.poc().compareTo(BigDecimal.valueOf(21002)));
    }

    // ─── Value area (70%) ──────────────────────────────────────────────────────

    @Test
    void valueArea_expandsAroundPoc_until70PercentCovered() {
        // Hand-built histogram via single-bucket candles:
        // 21000:50, 21001:100, 21002:400, 21003:200, 21004:50  (total 800, target 560)
        // Expansion from POC 21002 (400): +21003 (200) = 600 >= 560 -> VA = [21002, 21003]
        List<Candle> candles = List.of(
            candle(T0, 21000.2, 21000.8, 21000.2, 21000.5, 50),
            candle(T0.plusSeconds(60), 21001.2, 21001.8, 21001.2, 21001.5, 100),
            candle(T0.plusSeconds(120), 21002.2, 21002.8, 21002.2, 21002.5, 400),
            candle(T0.plusSeconds(180), 21003.2, 21003.8, 21003.2, 21003.5, 200),
            candle(T0.plusSeconds(240), 21004.2, 21004.8, 21004.2, 21004.5, 50));

        SessionProfile profile = calculator.compute(candles, ONE_POINT);

        assertNotNull(profile);
        assertEquals(0, profile.poc().compareTo(BigDecimal.valueOf(21002)));
        assertEquals(0, profile.vah().compareTo(BigDecimal.valueOf(21003)));
        assertEquals(0, profile.val().compareTo(BigDecimal.valueOf(21002)));
        assertEquals(800, profile.totalVolume());
        assertEquals(0, profile.rangeHigh().compareTo(BigDecimal.valueOf(21004.8)));
        assertEquals(0, profile.rangeLow().compareTo(BigDecimal.valueOf(21000.2)));
    }

    @Test
    void valueArea_tieExpandsUpward() {
        // 21000:100, 21001:300, 21002:100 (total 500, target 350)
        // POC 21001 (300) -> tie 100 vs 100 expands up first: VA = [21001, 21002]
        List<Candle> candles = List.of(
            candle(T0, 21000.2, 21000.8, 21000.2, 21000.5, 100),
            candle(T0.plusSeconds(60), 21001.2, 21001.8, 21001.2, 21001.5, 300),
            candle(T0.plusSeconds(120), 21002.2, 21002.8, 21002.2, 21002.5, 100));

        SessionProfile profile = calculator.compute(candles, ONE_POINT);

        assertNotNull(profile);
        assertEquals(0, profile.vah().compareTo(BigDecimal.valueOf(21002)));
        assertEquals(0, profile.val().compareTo(BigDecimal.valueOf(21001)));
    }

    @Test
    void emptyOrZeroVolume_returnsNull() {
        assertNull(calculator.compute(List.of(), ONE_POINT));
        assertNull(calculator.compute(
            List.of(candle(T0, 21000, 21001, 21000, 21000.5, 0)), ONE_POINT));
        assertNull(calculator.compute(null, ONE_POINT));
        assertNull(calculator.compute(
            List.of(candle(T0, 21000, 21001, 21000, 21000.5, 10)), BigDecimal.ZERO));
    }

    @Test
    void mclBucketSize_fiveCents() {
        // MCL-style buckets (0.05): candle inside [72.50, 72.55)
        List<Candle> candles = List.of(
            candle(T0, 72.51, 72.54, 72.51, 72.52, 100));

        SessionProfile profile = calculator.compute(candles, new BigDecimal("0.05"));

        assertNotNull(profile);
        assertEquals(0, profile.poc().compareTo(new BigDecimal("72.50")));
    }

    // ─── Naked POC ladder ──────────────────────────────────────────────────────

    private static SessionPocRange session(String date, double poc, double low, double high) {
        return new SessionPocRange(LocalDate.parse(date), BigDecimal.valueOf(poc),
            BigDecimal.valueOf(low), BigDecimal.valueOf(high));
    }

    @Test
    void nakedPoc_untouchedByLaterSessions_isNaked() {
        List<SessionPocRange> sessions = List.of(
            session("2026-04-13", 21000, 20950, 21050),   // POC 21000
            session("2026-04-14", 21200, 21100, 21300),   // never traded down to 21000
            session("2026-04-15", 21350, 21250, 21400));  // never traded down to 21000

        List<NakedPoc> naked = calculator.nakedPocs(sessions, null);

        // 21000 untouched; 21200 untouched (later range 21250-21400); 21350 is the
        // last session, nothing after it -> naked by definition
        assertEquals(3, naked.size());
        assertEquals(0, naked.get(0).price().compareTo(BigDecimal.valueOf(21000)));
        assertEquals(LocalDate.parse("2026-04-13"), naked.get(0).date());
    }

    @Test
    void nakedPoc_touchedByLaterSessionRange_isRemoved() {
        List<SessionPocRange> sessions = List.of(
            session("2026-04-13", 21000, 20950, 21050),
            session("2026-04-14", 21010, 20990, 21100)); // range covers 21000

        List<NakedPoc> naked = calculator.nakedPocs(sessions, null);

        assertEquals(1, naked.size());
        assertEquals(0, naked.get(0).price().compareTo(BigDecimal.valueOf(21010)));
    }

    @Test
    void nakedPoc_touchAtExactRangeBoundary_counts() {
        List<SessionPocRange> sessions = List.of(
            session("2026-04-13", 21000, 20950, 21050),
            session("2026-04-14", 21100, 21000, 21200)); // low touches POC exactly

        List<NakedPoc> naked = calculator.nakedPocs(sessions, null);

        assertEquals(1, naked.size());
        assertEquals(LocalDate.parse("2026-04-14"), naked.get(0).date());
    }

    @Test
    void nakedPoc_developingSessionRangeActsAsToucher() {
        List<SessionPocRange> sessions = List.of(
            session("2026-04-14", 21000, 20950, 21050));
        PriceRange developing = new PriceRange(
            BigDecimal.valueOf(20980), BigDecimal.valueOf(21020)); // covers 21000

        assertTrue(calculator.nakedPocs(sessions, developing).isEmpty());

        PriceRange notTouching = new PriceRange(
            BigDecimal.valueOf(21100), BigDecimal.valueOf(21200));
        assertEquals(1, calculator.nakedPocs(sessions, notTouching).size());
    }

    @Test
    void nakedPoc_emptyInput_returnsEmpty() {
        assertTrue(calculator.nakedPocs(List.of(), null).isEmpty());
        assertTrue(calculator.nakedPocs(null, null).isEmpty());
    }
}
