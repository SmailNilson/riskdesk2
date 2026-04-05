package com.riskdesk.domain.shared;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandleSeriesNormalizerTest {

    private static final BigDecimal ONE = BigDecimal.ONE;

    private static Candle candleAt(Instrument inst, int year, int month, int day, int hour, int minute) {
        Instant ts = ZonedDateTime.of(year, month, day, hour, minute, 0, 0,
                TradingSessionResolver.CME_ZONE).toInstant();
        return new Candle(inst, "10m", null, ts, ONE, ONE, ONE, ONE, 100);
    }

    @Test
    void purgeOutOfSession_removesWeekendCandles() {
        // Friday 16:00 ET (open), Saturday 10:00 ET (closed), Sunday 12:00 ET (closed), Sunday 18:00 ET (open)
        Candle fridayOpen = candleAt(Instrument.MCL, 2026, 3, 27, 16, 0);
        Candle saturdayClosed = candleAt(Instrument.MCL, 2026, 3, 28, 10, 0);
        Candle sundayClosed = candleAt(Instrument.MCL, 2026, 3, 29, 12, 0);
        Candle sundayOpen = candleAt(Instrument.MCL, 2026, 3, 29, 18, 0);

        List<Candle> filtered = CandleSeriesNormalizer.purgeOutOfSession(
                List.of(fridayOpen, saturdayClosed, sundayClosed, sundayOpen),
                Instrument.MCL
        );

        assertEquals(2, filtered.size());
        assertSame(fridayOpen, filtered.get(0));
        assertSame(sundayOpen, filtered.get(1));
    }

    @Test
    void purgeOutOfSession_removesFridayAfterClose() {
        // Friday 16:50 ET (open), Friday 17:00 ET (closed), Friday 19:00 ET (closed)
        Candle beforeClose = candleAt(Instrument.MGC, 2026, 3, 27, 16, 50);
        Candle atClose = candleAt(Instrument.MGC, 2026, 3, 27, 17, 0);
        Candle afterClose = candleAt(Instrument.MGC, 2026, 3, 27, 19, 0);

        List<Candle> filtered = CandleSeriesNormalizer.purgeOutOfSession(
                List.of(beforeClose, atClose, afterClose),
                Instrument.MGC
        );

        assertEquals(1, filtered.size());
        assertSame(beforeClose, filtered.get(0));
    }

    @Test
    void purgeOutOfSession_preservesOrderOldestToNewest() {
        // Monday 10:00 ET, Tuesday 14:00 ET, Wednesday 09:00 ET
        Candle mon = candleAt(Instrument.E6, 2026, 3, 23, 10, 0);
        Candle tue = candleAt(Instrument.E6, 2026, 3, 24, 14, 0);
        Candle wed = candleAt(Instrument.E6, 2026, 3, 25, 9, 0);

        List<Candle> filtered = CandleSeriesNormalizer.purgeOutOfSession(
                List.of(mon, tue, wed), Instrument.E6);

        assertEquals(3, filtered.size());
        assertEquals(List.of(mon, tue, wed), filtered);
    }

    @Test
    void purgeOutOfSession_syntheticInstrumentNeverFiltered() {
        // DXY on Saturday — should NOT be filtered
        Candle saturdayDxy = candleAt(Instrument.DXY, 2026, 3, 28, 10, 0);

        List<Candle> filtered = CandleSeriesNormalizer.purgeOutOfSession(
                List.of(saturdayDxy), Instrument.DXY);

        assertEquals(1, filtered.size());
        assertSame(saturdayDxy, filtered.get(0));
    }

    @Test
    void purgeOutOfSession_emptyListReturnsEmpty() {
        List<Candle> filtered = CandleSeriesNormalizer.purgeOutOfSession(
                List.of(), Instrument.MCL);
        assertTrue(filtered.isEmpty());
    }

    @Test
    void purgeOutOfSession_midweekAllPreserved() {
        // All midweek candles should be kept (Mon-Thu any time)
        Candle mon = candleAt(Instrument.MNQ, 2026, 3, 23, 2, 0);   // Monday 2am ET
        Candle tue = candleAt(Instrument.MNQ, 2026, 3, 24, 17, 30); // Tuesday 5:30pm ET (maintenance)
        Candle wed = candleAt(Instrument.MNQ, 2026, 3, 25, 23, 0);  // Wednesday 11pm ET
        Candle thu = candleAt(Instrument.MNQ, 2026, 3, 26, 0, 0);   // Thursday midnight ET

        List<Candle> filtered = CandleSeriesNormalizer.purgeOutOfSession(
                List.of(mon, tue, wed, thu), Instrument.MNQ);

        assertEquals(4, filtered.size());
    }
}
