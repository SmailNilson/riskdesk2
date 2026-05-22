package com.riskdesk.domain.playbook.automation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaybookRoutingPolicyTest {

    private final PlaybookRoutingPolicy policy = new PlaybookRoutingPolicy();

    @Test
    void fourOfSevenCreatesPaperOnlyWhenAutoIbkrIsOff() {
        PlaybookRoutingDecision result = policy.evaluate(decision(4, false, true), state(false, "DU123", 1));

        assertTrue(result.paperSimulationAllowed());
        assertFalse(result.liveRoutingAllowed());
        assertEquals(PlaybookRoutingOutcome.PAPER_ONLY, result.outcome());
    }

    @Test
    void fiveOfSevenCanRouteLiveWhenAutoIbkrIsOn() {
        PlaybookRoutingDecision result = policy.evaluate(decision(5, false, true), state(true, "DU123", 1));

        assertTrue(result.paperSimulationAllowed());
        assertTrue(result.liveRoutingAllowed());
    }

    @Test
    void belowPaperThresholdDoesNotCreatePaperOrLiveSideEffects() {
        PlaybookRoutingDecision result = policy.evaluate(decision(3, false, true), state(true, "DU123", 1));

        assertFalse(result.paperSimulationAllowed());
        assertFalse(result.liveRoutingAllowed());
        assertEquals(PlaybookRoutingOutcome.SKIPPED_BELOW_PAPER_THRESHOLD, result.outcome());
    }

    @Test
    void noPlanBlocksEverything() {
        PlaybookRoutingDecision result = policy.evaluate(decision(6, false, false), state(true, "DU123", 1));

        assertFalse(result.paperSimulationAllowed());
        assertFalse(result.liveRoutingAllowed());
        assertEquals(PlaybookRoutingOutcome.SKIPPED_NO_PLAN, result.outcome());
    }

    @Test
    void lateEntryStillAllowsPaperButBlocksLive() {
        PlaybookRoutingDecision result = policy.evaluate(decision(6, true, true), state(true, "DU123", 1));

        assertTrue(result.paperSimulationAllowed());
        assertFalse(result.liveRoutingAllowed());
        assertEquals(PlaybookRoutingOutcome.SKIPPED_LATE_ENTRY, result.outcome());
    }

    @Test
    void missingBrokerAccountBlocksLiveAfterPaper() {
        PlaybookRoutingDecision result = policy.evaluate(decision(6, false, true), state(true, null, 1));

        assertTrue(result.paperSimulationAllowed());
        assertFalse(result.liveRoutingAllowed());
        assertEquals(PlaybookRoutingOutcome.SKIPPED_NO_ACCOUNT, result.outcome());
    }

    private static PlaybookAutomationState state(boolean autoIbkr, String account, int qty) {
        return new PlaybookAutomationState(
            "MCL",
            "10m",
            4,
            5,
            true,
            autoIbkr,
            qty,
            account,
            Instant.parse("2026-05-22T12:00:00Z")
        );
    }

    private static PlaybookDecision decision(int score, boolean lateEntry, boolean completePlan) {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        return new PlaybookDecision(
            1L,
            "playbook:MCL:10m:1779451200:LONG:ZONE_RETEST:test",
            "MCL",
            "10m",
            "MCL:10m:1779451200:LONG:ZONE_RETEST:test",
            "ZONE_RETEST",
            "Test OB",
            "LONG",
            score,
            "TRADE",
            completePlan ? new BigDecimal("62.40") : null,
            completePlan ? new BigDecimal("61.90") : null,
            completePlan ? new BigDecimal("63.40") : null,
            completePlan ? new BigDecimal("64.40") : null,
            new BigDecimal("2.0"),
            new BigDecimal("0.005"),
            lateEntry,
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
