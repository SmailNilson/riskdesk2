package com.riskdesk.domain.engine.strategy.wtx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WtxSwingBiasFilterTest {

    @Test
    void longAlignedWithBullishBias_passesThrough() {
        assertEquals(
                WtxAction.OPEN_LONG,
                WtxSwingBiasFilter.filter("LONG", WtxAction.OPEN_LONG, "BULLISH", WtxPosition.FLAT));
        assertEquals(
                WtxAction.REVERSE_TO_LONG,
                WtxSwingBiasFilter.filter("LONG", WtxAction.REVERSE_TO_LONG, "BULLISH", WtxPosition.SHORT));
    }

    @Test
    void shortAlignedWithBearishBias_passesThrough() {
        assertEquals(
                WtxAction.OPEN_SHORT,
                WtxSwingBiasFilter.filter("SHORT", WtxAction.OPEN_SHORT, "BEARISH", WtxPosition.FLAT));
    }

    @Test
    void longContraBearishBias_whileFlat_skipsToNone() {
        assertEquals(
                WtxAction.NONE,
                WtxSwingBiasFilter.filter("LONG", WtxAction.OPEN_LONG, "BEARISH", WtxPosition.FLAT));
    }

    @Test
    void longContraBearishBias_whileLong_cutsThePosition() {
        assertEquals(
                WtxAction.CLOSE_LONG,
                WtxSwingBiasFilter.filter("LONG", WtxAction.REVERSE_TO_LONG, "BEARISH", WtxPosition.LONG));
    }

    @Test
    void shortContraBullishBias_whileShort_cutsThePosition() {
        assertEquals(
                WtxAction.CLOSE_SHORT,
                WtxSwingBiasFilter.filter("SHORT", WtxAction.REVERSE_TO_SHORT, "BULLISH", WtxPosition.SHORT));
    }

    @Test
    void nullBias_alwaysPassesThrough() {
        assertEquals(
                WtxAction.OPEN_LONG,
                WtxSwingBiasFilter.filter("LONG", WtxAction.OPEN_LONG, null, WtxPosition.FLAT));
        assertEquals(
                WtxAction.REVERSE_TO_SHORT,
                WtxSwingBiasFilter.filter("SHORT", WtxAction.REVERSE_TO_SHORT, null, WtxPosition.LONG));
    }

    @Test
    void noneAndCloseActions_areNeverFiltered() {
        assertEquals(WtxAction.NONE,
                WtxSwingBiasFilter.filter("LONG", WtxAction.NONE, "BEARISH", WtxPosition.FLAT));
        assertEquals(WtxAction.CLOSE_LONG,
                WtxSwingBiasFilter.filter("SHORT", WtxAction.CLOSE_LONG, "BULLISH", WtxPosition.LONG));
        assertEquals(WtxAction.CLOSE_SHORT,
                WtxSwingBiasFilter.filter("LONG", WtxAction.CLOSE_SHORT, "BEARISH", WtxPosition.SHORT));
        assertEquals(WtxAction.CLOSE_ALL,
                WtxSwingBiasFilter.filter("LONG", WtxAction.CLOSE_ALL, "BEARISH", WtxPosition.LONG));
    }
}
