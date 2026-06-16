package com.riskdesk.application.service;

import com.riskdesk.application.service.TradeSimulationService.SimState;
import com.riskdesk.application.service.TradeSimulationService.SimulationResult;
import com.riskdesk.application.service.TradeSimulationService.TradePlan;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.playbook.automation.PlaybookDecision;
import com.riskdesk.infrastructure.config.TrailingStopProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the STOP-entry semantics added for the Playbook confirmation profile
 * (PlaybookDecision.ENTRY_TYPE_STOP): trigger on break-through, pre-fill
 * invalidation → CANCELLED, no MISSED state — and proves LIMIT plans are
 * untouched (regression guard).
 */
class StopEntrySimulationTest {

    private final TradeSimulationService service = newService();

    private static TradeSimulationService newService() {
        TrailingStopProperties trailing = new TrailingStopProperties();
        trailing.setEnabled(false);
        return new TradeSimulationService(null, null, null, null, null, trailing, null);
    }

    private static SimState pending() {
        return new SimState(TradeSimulationStatus.PENDING_ENTRY, null, null, null, null, null, null);
    }

    /** LONG confirmation plan: buy-stop 28710, SL 28650, TP 28800, invalidation 28660. */
    private static TradePlan longStopPlan() {
        return new TradePlan(true,
            new BigDecimal("28710"), new BigDecimal("28650"), new BigDecimal("28800"),
            PlaybookDecision.ENTRY_TYPE_STOP, new BigDecimal("28660"));
    }

    private static Candle candle(int minute, String open, String high, String low, String close) {
        return new Candle(Instrument.MNQ, "1m",
            Instant.parse("2026-06-09T15:00:00Z").plusSeconds(minute * 60L),
            new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), 100);
    }

    @Test
    void buyStopTriggersWhenHighBreaksEntry() {
        List<Candle> candles = List.of(
            candle(0, "28690", "28700", "28685", "28695"),   // below trigger — no fill
            candle(1, "28695", "28715", "28690", "28712"),   // high crosses 28710 — ACTIVE
            candle(2, "28712", "28720", "28705", "28718"));
        SimulationResult result = service.evaluateWithPlan(longStopPlan(), pending(), candles);
        assertEquals(TradeSimulationStatus.ACTIVE, result.status());
        assertEquals(candles.get(1).getTimestamp(), result.activationTime());
    }

    @Test
    void limitSemanticsWouldHaveFilledThatFirstCandle() {
        // Same prices with a LIMIT plan fill immediately (low <= entry) — the stop
        // plan above did not: proves the two entry types diverge as intended.
        TradePlan limit = new TradePlan(true,
            new BigDecimal("28710"), new BigDecimal("28650"), new BigDecimal("28800"));
        List<Candle> candles = List.of(candle(0, "28690", "28700", "28685", "28695"));
        SimulationResult result = service.evaluateWithPlan(limit, pending(), candles);
        assertEquals(TradeSimulationStatus.ACTIVE, result.status());
    }

    @Test
    void zoneBreakBeforeTriggerCancelsThePendingSetup() {
        List<Candle> candles = List.of(
            candle(0, "28690", "28700", "28670", "28675"),   // dips, stays above invalidation
            candle(1, "28675", "28680", "28655", "28658"),   // low 28655 <= 28660 — zone broken
            candle(2, "28658", "28760", "28650", "28750"));  // later reclaim must NOT fill
        SimulationResult result = service.evaluateWithPlan(longStopPlan(), pending(), candles);
        assertEquals(TradeSimulationStatus.CANCELLED, result.status());
        assertEquals(candles.get(1).getTimestamp(), result.resolutionTime());
    }

    @Test
    void stopEntryNeverReportsMissed() {
        // Price opens already above the buy-stop and never trades back down to the
        // pre-trigger side: the breakout already happened, so there is no clean trigger.
        // A stop entry must NOT phantom-fill + win here (and never reports MISSED, which is
        // a limit-only state) — it simply stays pending, exactly like a live order that
        // can't trigger from the wrong side of the market.
        List<Candle> candles = List.of(
            candle(0, "28790", "28805", "28788", "28800"));
        SimulationResult result = service.evaluateWithPlan(longStopPlan(), pending(), candles);
        assertEquals(TradeSimulationStatus.PENDING_ENTRY, result.status());
    }

    @Test
    void sellStopDoesNotPhantomFillWhenPriceAlreadyBelowTrigger() {
        // Reproduces the reported discrepancy: a SHORT sell-stop placed at 30522.75 while
        // price is already below it. Price never trades back up to the trigger but drifts
        // down to the TP (30416.71). Live never fills the stop cleanly, so the paper replay
        // must NOT mark this a WIN — it stays pending (and later cancels on timeout).
        TradePlan shortPlan = new TradePlan(false,
            new BigDecimal("30522.75"), new BigDecimal("30593.44"), new BigDecimal("30416.71"),
            PlaybookDecision.ENTRY_TYPE_STOP, new BigDecimal("30650"));
        List<Candle> candles = List.of(
            candle(0, "30500.00", "30510.00", "30470.00", "30480.00"),   // already below trigger
            candle(1, "30480.00", "30485.00", "30430.00", "30440.00"),   // still below, no break from above
            candle(2, "30440.00", "30445.00", "30410.00", "30415.00"));  // dips through TP — but never armed
        SimulationResult result = service.evaluateWithPlan(shortPlan, pending(), candles);
        assertEquals(TradeSimulationStatus.PENDING_ENTRY, result.status());
    }

    @Test
    void sellStopFillsAfterCleanBreakFromAbove() {
        // Control for the case above: when price IS first seen on the pre-trigger side
        // (above the sell-stop) and then breaks down through it, the fill is genuine.
        TradePlan shortPlan = new TradePlan(false,
            new BigDecimal("30522.75"), new BigDecimal("30593.44"), new BigDecimal("30416.71"),
            PlaybookDecision.ENTRY_TYPE_STOP, new BigDecimal("30650"));
        List<Candle> candles = List.of(
            candle(0, "30540.00", "30560.00", "30530.00", "30535.00"),   // above trigger — armed
            candle(1, "30535.00", "30538.00", "30410.00", "30420.00"));  // breaks down through entry → TP
        SimulationResult result = service.evaluateWithPlan(shortPlan, pending(), candles);
        assertEquals(TradeSimulationStatus.WIN, result.status());
    }

    @Test
    void filledStopEntryResolvesPessimisticallyOnStopFirst() {
        List<Candle> candles = List.of(
            candle(0, "28695", "28715", "28690", "28710"),   // fill at trigger
            candle(1, "28710", "28805", "28645", "28700"));  // touches BOTH SL and TP → LOSS
        SimulationResult result = service.evaluateWithPlan(longStopPlan(), pending(), candles);
        assertEquals(TradeSimulationStatus.LOSS, result.status());
    }

    @Test
    void shortStopMirror() {
        TradePlan shortPlan = new TradePlan(false,
            new BigDecimal("28680"), new BigDecimal("28740"), new BigDecimal("28590"),
            PlaybookDecision.ENTRY_TYPE_STOP, new BigDecimal("28730"));
        List<Candle> candles = List.of(
            candle(0, "28700", "28725", "28690", "28695"),   // above trigger, below invalidation
            candle(1, "28695", "28700", "28675", "28678"),   // low crosses 28680 — ACTIVE
            candle(2, "28678", "28685", "28585", "28600"));  // TP 28590 touched → WIN
        SimulationResult result = service.evaluateWithPlan(shortPlan, pending(), candles);
        assertEquals(TradeSimulationStatus.WIN, result.status());
    }

    @Test
    void invalidationIgnoredForLimitPlans() {
        // A LIMIT plan carrying an invalidation level by mistake must not cancel —
        // invalidation is a stop-entry concept only.
        TradePlan limit = new TradePlan(true,
            new BigDecimal("28710"), new BigDecimal("28650"), new BigDecimal("28800"),
            null, new BigDecimal("28660"));
        List<Candle> candles = List.of(
            candle(0, "28755", "28760", "28750", "28752"),   // above entry — limit waits
            candle(1, "28752", "28756", "28655", "28700"));  // would have hit "invalidation"
        SimulationResult result = service.evaluateWithPlan(limit, pending(), candles);
        // limit fills at 28710 on the way down (low 28655 <= entry), then low <= SL? 28655 > 28650 → ACTIVE
        assertEquals(TradeSimulationStatus.ACTIVE, result.status());
    }
}
