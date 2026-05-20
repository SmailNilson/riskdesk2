package com.riskdesk.domain.engine.strategy.wtx;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WtxHtfBiasFilterTest {

    private static final BigDecimal P100 = BigDecimal.valueOf(100);
    private static final BigDecimal P101 = BigDecimal.valueOf(101);
    private static final BigDecimal P102 = BigDecimal.valueOf(102);
    private static final BigDecimal P99  = BigDecimal.valueOf(99);
    private static final BigDecimal P98  = BigDecimal.valueOf(98);
    private static final BigDecimal P97  = BigDecimal.valueOf(97);

    @Test
    void bullishStack_allowsLong_blocksShort() {
        WtxHtfBiasFilter.HtfBiasContext ctx = new WtxHtfBiasFilter.HtfBiasContext(P102, P101, P100);
        assertTrue(WtxHtfBiasFilter.evaluate("LONG", ctx).allows());
        assertFalse(WtxHtfBiasFilter.evaluate("SHORT", ctx).allows());
        assertEquals(WtxHtfBiasFilter.HtfBias.BULLISH, WtxHtfBiasFilter.evaluate("LONG", ctx).bias());
    }

    @Test
    void bearishStack_blocksLong_allowsShort() {
        WtxHtfBiasFilter.HtfBiasContext ctx = new WtxHtfBiasFilter.HtfBiasContext(P97, P98, P99);
        assertFalse(WtxHtfBiasFilter.evaluate("LONG", ctx).allows());
        assertTrue(WtxHtfBiasFilter.evaluate("SHORT", ctx).allows());
        assertEquals(WtxHtfBiasFilter.HtfBias.BEARISH, WtxHtfBiasFilter.evaluate("SHORT", ctx).bias());
    }

    @Test
    void neutralStack_allowsBoth() {
        // close=100, fast=99, slow=101 → neither stacked bullish nor bearish
        WtxHtfBiasFilter.HtfBiasContext ctx = new WtxHtfBiasFilter.HtfBiasContext(P100, P99, P101);
        assertTrue(WtxHtfBiasFilter.evaluate("LONG", ctx).allows());
        assertTrue(WtxHtfBiasFilter.evaluate("SHORT", ctx).allows());
        assertEquals(WtxHtfBiasFilter.HtfBias.NEUTRAL, WtxHtfBiasFilter.evaluate("LONG", ctx).bias());
    }

    @Test
    void missingInputs_failSafePermissive() {
        WtxHtfBiasFilter.HtfBiasContext ctx = new WtxHtfBiasFilter.HtfBiasContext(null, P100, P99);
        WtxHtfBiasFilter.Decision d = WtxHtfBiasFilter.evaluate("LONG", ctx);
        assertTrue(d.allows());
        assertEquals(WtxHtfBiasFilter.HtfBias.UNAVAILABLE, d.bias());
    }

    @Test
    void nullContext_failSafePermissive() {
        WtxHtfBiasFilter.Decision d = WtxHtfBiasFilter.evaluate("SHORT", null);
        assertTrue(d.allows());
        assertEquals(WtxHtfBiasFilter.HtfBias.UNAVAILABLE, d.bias());
    }
}
