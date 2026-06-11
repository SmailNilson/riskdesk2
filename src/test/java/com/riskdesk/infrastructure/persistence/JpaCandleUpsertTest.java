package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Candle writes must be upserts on (instrument, timeframe, timestamp): the live accumulator
 * and the IBKR backfill both produce the same bar, and at boot the gap-fill races the live
 * writer on just-closed bars. Before the upsert, the duplicate insert blew up on
 * uk_candle_instrument_tf_ts and aborted the WHOLE backfill batch.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(JpaCandleRepositoryAdapter.class)
class JpaCandleUpsertTest {

    @Autowired
    private JpaCandleRepositoryAdapter adapter;

    private static Candle candle(Instrument inst, String tf, String ts, long volume, String close) {
        BigDecimal p = new BigDecimal(close);
        return new Candle(inst, tf, Instant.parse(ts), p, p, p, p, volume);
    }

    @Test
    void save_onExistingNaturalKey_updatesInsteadOfThrowing() {
        adapter.save(candle(Instrument.MNQ, "1m", "2026-06-10T14:30:00Z", 120L, "21500.25"));

        assertDoesNotThrow(() ->
            adapter.save(candle(Instrument.MNQ, "1m", "2026-06-10T14:30:00Z", 4480L, "21501.50")));

        List<Candle> stored = adapter.findCandlesBetween(Instrument.MNQ, "1m",
            Instant.parse("2026-06-10T14:00:00Z"), Instant.parse("2026-06-10T15:00:00Z"));
        assertEquals(1, stored.size());
        assertEquals(4480L, stored.get(0).getVolume());
        assertEquals(0, new BigDecimal("21501.50").compareTo(stored.get(0).getClose()));
    }

    @Test
    void saveAll_overlappingExistingBars_upsertsWholeBatch() {
        // A live-built bar already stored — the backfill batch then covers the same minute.
        adapter.save(candle(Instrument.MNQ, "1m", "2026-06-10T14:30:00Z", 120L, "21500.25"));

        assertDoesNotThrow(() -> adapter.saveAll(List.of(
            candle(Instrument.MNQ, "1m", "2026-06-10T14:29:00Z", 4100L, "21499.00"),
            candle(Instrument.MNQ, "1m", "2026-06-10T14:30:00Z", 4480L, "21501.50"),
            candle(Instrument.MNQ, "1m", "2026-06-10T14:31:00Z", 3900L, "21503.75")
        )));

        List<Candle> stored = adapter.findCandlesBetween(Instrument.MNQ, "1m",
            Instant.parse("2026-06-10T14:00:00Z"), Instant.parse("2026-06-10T15:00:00Z"));
        assertEquals(3, stored.size());
        assertEquals(4480L, stored.stream()
            .filter(c -> c.getTimestamp().equals(Instant.parse("2026-06-10T14:30:00Z")))
            .findFirst().orElseThrow().getVolume());
    }

    @Test
    void saveAll_isScopedPerInstrumentAndTimeframe() {
        // Same timestamp on another timeframe/instrument must stay an insert, not an update.
        adapter.save(candle(Instrument.MNQ, "1m", "2026-06-10T14:30:00Z", 100L, "21500.00"));

        adapter.saveAll(List.of(
            candle(Instrument.MNQ, "5m", "2026-06-10T14:30:00Z", 200L, "21500.00"),
            candle(Instrument.MGC, "1m", "2026-06-10T14:30:00Z", 300L, "3350.00")
        ));

        assertEquals(1, adapter.findCandlesBetween(Instrument.MNQ, "1m",
            Instant.parse("2026-06-10T14:30:00Z"), Instant.parse("2026-06-10T14:30:00Z")).size());
        assertEquals(1, adapter.findCandlesBetween(Instrument.MNQ, "5m",
            Instant.parse("2026-06-10T14:30:00Z"), Instant.parse("2026-06-10T14:30:00Z")).size());
        assertEquals(1, adapter.findCandlesBetween(Instrument.MGC, "1m",
            Instant.parse("2026-06-10T14:30:00Z"), Instant.parse("2026-06-10T14:30:00Z")).size());
    }
}
