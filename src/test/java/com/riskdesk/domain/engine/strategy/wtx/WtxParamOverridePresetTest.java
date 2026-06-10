package com.riskdesk.domain.engine.strategy.wtx;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the named {@link WtxParamOverride} presets — in particular {@code top-train-Z35},
 * the zone-entry configuration validated by the real-1m study (mars→juin 2026, MNQ 10m).
 */
class WtxParamOverridePresetTest {

    @Test
    void topTrainZ35_carriesTheValidatedConfiguration() {
        WtxParamOverride p = WtxParamOverride.TOP_TRAIN_Z35;
        assertEquals(5, p.n1());
        assertEquals(14, p.n2());
        assertEquals(2, p.signalPeriod());
        assertEquals(0, new BigDecimal("4.0").compareTo(p.slAtrMult()));
        assertEquals(0, BigDecimal.valueOf(35).compareTo(p.nsc()));
        assertEquals(0, BigDecimal.valueOf(-35).compareTo(p.nsv()));
        assertEquals(Boolean.FALSE, p.useCompra1());
        assertEquals(Boolean.FALSE, p.useVenta1());
        assertFalse(p.isEmpty());
    }

    @Test
    void presetLookup_isCaseAndWhitespaceInsensitive() {
        assertEquals(Optional.of(WtxParamOverride.TOP_TRAIN_Z35), WtxParamOverride.preset("top-train-z35"));
        assertEquals(Optional.of(WtxParamOverride.TOP_TRAIN_Z35), WtxParamOverride.preset("  TOP-TRAIN-Z35  "));
    }

    @Test
    void presetLookup_clearResolvesToNone_andUnknownIsEmpty() {
        assertEquals(Optional.of(WtxParamOverride.NONE), WtxParamOverride.preset("clear"));
        assertTrue(WtxParamOverride.preset("does-not-exist").isEmpty());
        assertTrue(WtxParamOverride.preset(null).isEmpty());
        assertTrue(WtxParamOverride.preset("  ").isEmpty());
    }

    @Test
    void isEmpty_requiresEveryFieldNull() {
        assertTrue(WtxParamOverride.NONE.isEmpty());
        assertFalse(new WtxParamOverride(null, null, null, null,
                BigDecimal.valueOf(35), null, null, null).isEmpty());
        assertFalse(new WtxParamOverride(null, null, null, null,
                null, null, Boolean.FALSE, null).isEmpty());
    }
}
