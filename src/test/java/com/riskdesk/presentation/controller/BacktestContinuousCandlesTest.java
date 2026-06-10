package com.riskdesk.presentation.controller;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link BacktestController#buildContinuousCandles}: CONT-tagged candles
 * (continuous CONTFUT re-source) must behave like the legacy untagged base layer. Sorting "CONT"
 * as a contract-month tag would place it after every "yyyyMM" tag, and the roll-date splice would
 * then silently drop the whole re-sourced window (it only keeps back-month bars NEWER than the
 * series' max timestamp — a re-sourced window is by definition older).
 */
class BacktestContinuousCandlesTest {

    private static Candle tagged(String month, String ts) {
        BigDecimal p = BigDecimal.valueOf(100);
        Candle c = new Candle(Instrument.MNQ, "1m", Instant.parse(ts), p, p, p, p, 1L);
        c.setContractMonth(month);
        return c;
    }

    @Test
    void contRows_surviveTheSplice_asBaseLayer() {
        // A re-sourced January window (CONT) + two real contract months (March/June coverage).
        List<Candle> raw = List.of(
            tagged("CONT",   "2026-01-10T00:00:00Z"),
            tagged("CONT",   "2026-02-10T00:00:00Z"),
            tagged("202603", "2026-03-05T00:00:00Z"),
            tagged("202606", "2026-04-20T00:00:00Z"),
            tagged("202606", "2026-06-01T00:00:00Z"));

        List<Candle> spliced = BacktestController.buildContinuousCandles(raw, Instrument.MNQ, "1m");

        assertEquals(5, spliced.size(), "the CONT window must not be dropped by the roll-date cut");
        assertEquals(Instant.parse("2026-01-10T00:00:00Z"), spliced.get(0).getTimestamp());
        assertTrue(spliced.stream().map(Candle::getTimestamp).sorted().toList()
            .equals(spliced.stream().map(Candle::getTimestamp).toList()), "series must be time-ordered");
    }

    @Test
    void contOnlyDataset_passesThroughUnchanged() {
        List<Candle> raw = List.of(
            tagged("CONT", "2026-01-10T00:00:00Z"),
            tagged("CONT", "2026-01-11T00:00:00Z"));

        List<Candle> spliced = BacktestController.buildContinuousCandles(raw, Instrument.MNQ, "1m");

        assertEquals(2, spliced.size());
    }
}
