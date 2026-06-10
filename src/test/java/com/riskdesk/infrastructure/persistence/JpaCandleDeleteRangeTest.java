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

/**
 * Integration test for {@link CandleRepository#deleteRange} through the adapter — a
 * {@code @Modifying @Query} bulk delete only proves itself against a real EntityManager.
 * The window must be closed ([from, to] inclusive) and scoped to the exact
 * (instrument, timeframe) pair: purging MNQ 1m must never touch 5m rows or other instruments.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(JpaCandleRepositoryAdapter.class)
class JpaCandleDeleteRangeTest {

    @Autowired
    private JpaCandleRepositoryAdapter adapter;

    private static Candle candle(Instrument inst, String tf, String ts) {
        BigDecimal p = BigDecimal.valueOf(100);
        return new Candle(inst, tf, Instant.parse(ts), p, p, p, p, 1L);
    }

    @Test
    void deleteRange_isInclusive_andScopedToInstrumentAndTimeframe() {
        adapter.saveAll(List.of(
            candle(Instrument.MNQ, "1m", "2026-01-01T00:00:00Z"),  // = from → deleted
            candle(Instrument.MNQ, "1m", "2026-01-15T00:00:00Z"),  // inside  → deleted
            candle(Instrument.MNQ, "1m", "2026-03-01T00:00:00Z"),  // = to    → deleted
            candle(Instrument.MNQ, "1m", "2026-03-01T00:01:00Z"),  // after   → kept
            candle(Instrument.MNQ, "5m", "2026-01-15T00:00:00Z"),  // other tf → kept
            candle(Instrument.MGC, "1m", "2026-01-15T00:00:00Z")   // other instrument → kept
        ));

        int deleted = adapter.deleteRange(Instrument.MNQ, "1m",
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z"));

        assertEquals(3, deleted);
        assertEquals(1, adapter.findCandlesBetween(Instrument.MNQ, "1m",
            Instant.parse("2025-12-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z")).size());
        assertEquals(1, adapter.findCandlesBetween(Instrument.MNQ, "5m",
            Instant.parse("2025-12-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z")).size());
        assertEquals(1, adapter.findCandlesBetween(Instrument.MGC, "1m",
            Instant.parse("2025-12-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z")).size());
    }

    @Test
    void deleteRange_returnsZeroOnEmptyWindow() {
        int deleted = adapter.deleteRange(Instrument.MNQ, "1m",
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z"));
        assertEquals(0, deleted);
    }
}
