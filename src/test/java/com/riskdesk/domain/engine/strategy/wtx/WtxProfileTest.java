package com.riskdesk.domain.engine.strategy.wtx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WtxProfileTest {

    @Test
    void baseline_disablesAllExtraGates() {
        WtxProfile p = WtxProfile.BASELINE;
        assertFalse(p.blocksOnMaxLoss());
        assertFalse(p.requiresAtrExits());
        assertFalse(p.requiresHtfFilter());
        assertFalse(p.requiresStructureFilter());
    }

    @Test
    void sessionAtr_enablesMaxLossAndAtrOnly() {
        WtxProfile p = WtxProfile.SESSION_ATR;
        assertTrue(p.blocksOnMaxLoss());
        assertTrue(p.requiresAtrExits());
        assertFalse(p.requiresHtfFilter());
        assertFalse(p.requiresStructureFilter());
    }

    @Test
    void htf_addsHtfBias() {
        WtxProfile p = WtxProfile.HTF;
        assertTrue(p.blocksOnMaxLoss());
        assertTrue(p.requiresAtrExits());
        assertTrue(p.requiresHtfFilter());
        assertFalse(p.requiresStructureFilter());
    }

    @Test
    void strict_enablesEverything() {
        WtxProfile p = WtxProfile.STRICT;
        assertTrue(p.blocksOnMaxLoss());
        assertTrue(p.requiresAtrExits());
        assertTrue(p.requiresHtfFilter());
        assertTrue(p.requiresStructureFilter());
    }
}
