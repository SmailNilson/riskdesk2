package com.riskdesk.domain.playbook.automation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybookDecisionTest {

    @Test
    void routingOutcome_enumCoversPaperRouteAndDiagnosableSkipStates() {
        EnumSet<PlaybookRoutingOutcome> outcomes = EnumSet.allOf(PlaybookRoutingOutcome.class);

        assertTrue(outcomes.contains(PlaybookRoutingOutcome.PAPER_ONLY));
        assertTrue(outcomes.contains(PlaybookRoutingOutcome.ROUTED));
        assertTrue(outcomes.contains(PlaybookRoutingOutcome.ACK_PENDING));
        assertTrue(outcomes.contains(PlaybookRoutingOutcome.SKIPPED_AUTO_OFF));
        assertTrue(outcomes.contains(PlaybookRoutingOutcome.SKIPPED_BELOW_PAPER_THRESHOLD));
        assertTrue(outcomes.contains(PlaybookRoutingOutcome.SKIPPED_NO_PLAN));
        assertTrue(outcomes.contains(PlaybookRoutingOutcome.SKIPPED_NO_QTY));
        assertTrue(outcomes.contains(PlaybookRoutingOutcome.SKIPPED_NO_ACCOUNT));
        assertTrue(outcomes.contains(PlaybookRoutingOutcome.SKIPPED_STALE_PRICE_SOURCE));
        assertTrue(outcomes.contains(PlaybookRoutingOutcome.SKIPPED_IBKR_DISABLED));
        assertTrue(outcomes.contains(PlaybookRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN));
    }

    @Test
    void withRouting_attachesOutcomeErrorAndExecutionWithoutChangingPlanIdentity() {
        PlaybookDecision decision = decision();

        PlaybookDecision routed = decision.withRouting(
            PlaybookRoutingOutcome.ROUTED,
            null,
            42L
        );

        assertEquals(decision.decisionKey(), routed.decisionKey());
        assertEquals(decision.instrument(), routed.instrument());
        assertEquals(decision.timeframe(), routed.timeframe());
        assertEquals(decision.evaluatedCandleTs(), routed.evaluatedCandleTs());
        assertEquals(PlaybookRoutingOutcome.ROUTED, routed.routingOutcome());
        assertNull(routed.routingErrorMessage());
        assertEquals(42L, routed.executionId());
    }

    @Test
    void withRouting_preservesDiagnosableSkipReasonWhenBrokerRouteIsNotAttempted() {
        PlaybookDecision decision = decision();

        PlaybookDecision skipped = decision.withRouting(
            PlaybookRoutingOutcome.SKIPPED_NO_ACCOUNT,
            "Broker account is required before Auto-IBKR routing",
            null
        );

        assertEquals(PlaybookRoutingOutcome.SKIPPED_NO_ACCOUNT, skipped.routingOutcome());
        assertEquals("Broker account is required before Auto-IBKR routing", skipped.routingErrorMessage());
        assertNull(skipped.executionId());
    }

    @Test
    void constructor_requiresStableDecisionIdentity() {
        PlaybookDecision base = decision();

        assertThrows(NullPointerException.class, () -> new PlaybookDecision(
            base.id(),
            null,
            base.instrument(),
            base.timeframe(),
            base.setupIdentity(),
            base.setupType(),
            base.zoneName(),
            base.direction(),
            base.checklistScore(),
            base.verdict(),
            base.entryPrice(),
            base.stopLoss(),
            base.takeProfit1(),
            base.takeProfit2(),
            base.rrRatio(),
            base.riskPercent(),
            base.lateEntry(),
            base.priceSource(),
            base.priceTimestamp(),
            base.evaluatedCandleTs(),
            base.createdAt(),
            base.routingOutcome(),
            base.routingErrorMessage(),
            base.executionId()
        ));
    }

    private static PlaybookDecision decision() {
        Instant candleTs = Instant.parse("2026-05-22T13:30:00Z");
        Instant createdAt = Instant.parse("2026-05-22T13:30:05Z");
        return new PlaybookDecision(
            7L,
            "playbook:MCL:10m:20260522T133000Z:LONG",
            "MCL",
            "10m",
            "ZONE_RETEST:Bullish OB:LONG",
            "ZONE_RETEST",
            "Bullish OB",
            "LONG",
            6,
            "TRADE",
            new BigDecimal("62.40"),
            new BigDecimal("61.90"),
            new BigDecimal("63.40"),
            new BigDecimal("64.40"),
            new BigDecimal("2.00"),
            new BigDecimal("0.50"),
            false,
            "LIVE_IBKR",
            createdAt,
            candleTs,
            createdAt,
            PlaybookRoutingOutcome.PAPER_ONLY,
            null,
            null
        );
    }
}
