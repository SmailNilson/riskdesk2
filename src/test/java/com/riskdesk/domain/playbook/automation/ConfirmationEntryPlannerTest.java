package com.riskdesk.domain.playbook.automation;

import com.riskdesk.domain.playbook.automation.ConfirmationEntryPlanner.ConfirmationPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmationEntryPlannerTest {

    private static final BigDecimal ZONE_HIGH = new BigDecimal("28710.00");
    private static final BigDecimal ZONE_LOW = new BigDecimal("28680.00");
    private static final BigDecimal ATR = new BigDecimal("40.00");

    // Tuesday 2026-06-09 15:00Z = 11:00 ET (EDT) — inside RTH
    private static final Instant RTH_EDT = Instant.parse("2026-06-09T15:00:00Z");
    // Tuesday 2026-01-13 15:00Z = 10:00 ET (EST) — inside RTH across the DST flip
    private static final Instant RTH_EST = Instant.parse("2026-01-13T15:00:00Z");
    // Tuesday 2026-06-09 12:30Z = 08:30 ET — day window but pre-RTH
    private static final Instant PRE_RTH = Instant.parse("2026-06-09T12:30:00Z");
    // Tuesday 2026-06-09 04:00Z = 00:00 ET — Globex overnight
    private static final Instant OVERNIGHT = Instant.parse("2026-06-09T04:00:00Z");
    // Saturday 2026-06-06 15:00Z — weekend
    private static final Instant WEEKEND = Instant.parse("2026-06-06T15:00:00Z");
    // RTH close boundary: 2026-06-09 20:00Z = 16:00 ET exactly — excluded
    private static final Instant RTH_CLOSE = Instant.parse("2026-06-09T20:00:00Z");

    @Test
    void longPlanIsStopAtZoneHighWithAtrBracketsAndInvalidationBelowZone() {
        ConfirmationPlan plan = ConfirmationEntryPlanner
            .plan("LONG", 5, ZONE_HIGH, ZONE_LOW, ATR, RTH_EDT)
            .orElseThrow();

        assertEquals(new BigDecimal("28710.00"), plan.entryPrice());
        assertEquals(new BigDecimal("28650.00"), plan.stopLoss());        // entry − 1.5×ATR
        assertEquals(new BigDecimal("28800.00"), plan.takeProfit());      // entry + 2.25×ATR
        assertEquals(new BigDecimal("28660.00"), plan.invalidationPrice()); // zoneLow − 0.5×ATR
        assertEquals(0, plan.rrRatio().compareTo(new BigDecimal("1.5")));
    }

    @Test
    void shortPlanIsStopAtZoneLowMirrored() {
        ConfirmationPlan plan = ConfirmationEntryPlanner
            .plan("SHORT", 6, ZONE_HIGH, ZONE_LOW, ATR, RTH_EDT)
            .orElseThrow();

        assertEquals(new BigDecimal("28680.00"), plan.entryPrice());
        assertEquals(new BigDecimal("28740.00"), plan.stopLoss());        // entry + 1.5×ATR
        assertEquals(new BigDecimal("28590.00"), plan.takeProfit());      // entry − 2.25×ATR
        assertEquals(new BigDecimal("28730.00"), plan.invalidationPrice()); // zoneHigh + 0.5×ATR
    }

    @Test
    void longSessionGateIsRthOnlyDstAware() {
        assertTrue(ConfirmationEntryPlanner.plan("LONG", 5, ZONE_HIGH, ZONE_LOW, ATR, RTH_EDT).isPresent());
        assertTrue(ConfirmationEntryPlanner.plan("LONG", 5, ZONE_HIGH, ZONE_LOW, ATR, RTH_EST).isPresent());
        assertTrue(ConfirmationEntryPlanner.plan("LONG", 5, ZONE_HIGH, ZONE_LOW, ATR, PRE_RTH).isEmpty());
        assertTrue(ConfirmationEntryPlanner.plan("LONG", 5, ZONE_HIGH, ZONE_LOW, ATR, OVERNIGHT).isEmpty());
        assertTrue(ConfirmationEntryPlanner.plan("LONG", 5, ZONE_HIGH, ZONE_LOW, ATR, WEEKEND).isEmpty());
        assertTrue(ConfirmationEntryPlanner.plan("LONG", 5, ZONE_HIGH, ZONE_LOW, ATR, RTH_CLOSE).isEmpty());
    }

    @Test
    void shortSessionGateAdmitsExtendedDayWindowButNotOvernight() {
        assertTrue(ConfirmationEntryPlanner.plan("SHORT", 5, ZONE_HIGH, ZONE_LOW, ATR, PRE_RTH).isPresent());
        assertTrue(ConfirmationEntryPlanner.plan("SHORT", 5, ZONE_HIGH, ZONE_LOW, ATR, RTH_EDT).isPresent());
        assertTrue(ConfirmationEntryPlanner.plan("SHORT", 5, ZONE_HIGH, ZONE_LOW, ATR, OVERNIGHT).isEmpty());
        assertTrue(ConfirmationEntryPlanner.plan("SHORT", 5, ZONE_HIGH, ZONE_LOW, ATR, WEEKEND).isEmpty());
    }

    @Test
    void gatesRejectLowScoreMissingZoneOrAtr() {
        assertTrue(ConfirmationEntryPlanner.plan("LONG", 4, ZONE_HIGH, ZONE_LOW, ATR, RTH_EDT).isEmpty());
        assertTrue(ConfirmationEntryPlanner.plan("LONG", 5, null, ZONE_LOW, ATR, RTH_EDT).isEmpty());
        assertTrue(ConfirmationEntryPlanner.plan("LONG", 5, ZONE_LOW, ZONE_HIGH, ATR, RTH_EDT).isEmpty()); // inverted
        assertTrue(ConfirmationEntryPlanner.plan("LONG", 5, ZONE_HIGH, ZONE_LOW, null, RTH_EDT).isEmpty());
        assertTrue(ConfirmationEntryPlanner.plan("LONG", 5, ZONE_HIGH, ZONE_LOW, BigDecimal.ZERO, RTH_EDT).isEmpty());
    }

    private static Optional<ConfirmationPlan> planAt(Instant ts) {
        return ConfirmationEntryPlanner.plan("LONG", 5, ZONE_HIGH, ZONE_LOW, ATR, ts);
    }

    @Test
    void rthOpenBoundaryIsInclusive() {
        // 2026-06-09 13:30Z = 09:30 ET exactly — first eligible minute
        assertTrue(planAt(Instant.parse("2026-06-09T13:30:00Z")).isPresent());
        // one minute before
        assertTrue(planAt(Instant.parse("2026-06-09T13:29:00Z")).isEmpty());
    }
}
