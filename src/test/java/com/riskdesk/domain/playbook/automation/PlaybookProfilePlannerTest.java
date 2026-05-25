package com.riskdesk.domain.playbook.automation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybookProfilePlannerTest {

    @Test
    void mgcScalpProfileUsesSameEntryAndStopWithHalfRTarget() {
        PlaybookDecision decision = decision("MGC", "10m", "BREAK_RETEST", "SHORT");

        PlaybookProfilePlan plan = PlaybookProfilePlanner.planFor(
            decision, PlaybookExecutionProfile.MGC_10M_SCALP_0_5R);

        assertEquals(PlaybookExecutionProfile.MGC_10M_SCALP_0_5R, plan.profile());
        assertEquals(new BigDecimal("4513.40"), plan.entryPrice());
        assertEquals(new BigDecimal("4564.40"), plan.stopLoss());
        assertEquals(new BigDecimal("4487.90"), plan.takeProfit());
        assertEquals(new BigDecimal("0.5"), plan.targetR());
        assertTrue(plan.executable());
    }

    @Test
    void oneRProfileIsBenchmarkOnly() {
        PlaybookDecision decision = decision("MGC", "10m", "BREAK_RETEST", "LONG");

        PlaybookProfilePlan plan = PlaybookProfilePlanner.planFor(
            decision, PlaybookExecutionProfile.MGC_10M_NORMAL_1R_BENCHMARK);

        assertEquals(new BigDecimal("4564.40"), plan.takeProfit());
        assertEquals(BigDecimal.ONE, plan.targetR());
        assertFalse(plan.executable());
    }

    @Test
    void unsupportedScopedProfileFallsBackToLegacy() {
        PlaybookDecision decision = decision("MNQ", "10m", "BREAK_RETEST", "LONG");

        PlaybookProfilePlan plan = PlaybookProfilePlanner.planFor(
            decision, PlaybookExecutionProfile.MGC_10M_SCALP_0_5R);

        assertEquals(PlaybookExecutionProfile.LEGACY, plan.profile());
        assertEquals(decision.takeProfit1(), plan.takeProfit());
    }

    private static PlaybookDecision decision(String instrument, String timeframe, String setupType, String direction) {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        return new PlaybookDecision(
            1L,
            "playbook:" + instrument + ":" + timeframe + ":1779451200:" + direction + ":" + setupType + ":test",
            instrument,
            timeframe,
            instrument + ":" + timeframe + ":1779451200:" + direction + ":" + setupType + ":test",
            setupType,
            "BOS Retest",
            direction,
            5,
            "TRADE",
            new BigDecimal("4513.40"),
            direction.equals("SHORT") ? new BigDecimal("4564.40") : new BigDecimal("4462.40"),
            direction.equals("SHORT") ? new BigDecimal("4508.40") : new BigDecimal("4518.40"),
            direction.equals("SHORT") ? new BigDecimal("4489.60") : new BigDecimal("4537.20"),
            new BigDecimal("0.1"),
            new BigDecimal("0.005"),
            false,
            "LIVE_IBKR",
            now,
            now,
            now,
            null,
            null,
            null
        );
    }
}
