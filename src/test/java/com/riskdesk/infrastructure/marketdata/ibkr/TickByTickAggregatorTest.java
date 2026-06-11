package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.riskdesk.infrastructure.marketdata.ibkr.TickByTickAggregator.TickClassification.*;
import static org.junit.jupiter.api.Assertions.*;

class TickByTickAggregatorTest {

    @Test
    void emptyAggregatorReturnsFlat() {
        var agg = new TickByTickAggregator(Instrument.MCL);
        var snapshot = agg.snapshot();
        assertEquals(0, snapshot.buyVolume());
        assertEquals(0, snapshot.sellVolume());
        assertEquals("FLAT", snapshot.deltaTrend());
        assertFalse(snapshot.divergenceDetected());
        assertEquals("REAL_TICKS", snapshot.source());
    }

    @Test
    void allBuysPositiveDelta() {
        var agg = new TickByTickAggregator(Instrument.MCL);
        var now = Instant.now();
        agg.onTick(100.0, 10, BUY, now);
        agg.onTick(100.5, 20, BUY, now.plusSeconds(1));
        agg.onTick(101.0, 15, BUY, now.plusSeconds(2));

        var snapshot = agg.snapshot();
        assertEquals(45, snapshot.buyVolume());
        assertEquals(0, snapshot.sellVolume());
        assertEquals(45, snapshot.delta());
        assertEquals(100.0, snapshot.buyRatioPct(), 0.1);
    }

    @Test
    void allSellsNegativeDelta() {
        var agg = new TickByTickAggregator(Instrument.MGC);
        var now = Instant.now();
        agg.onTick(2000.0, 5, SELL, now);
        agg.onTick(1999.0, 10, SELL, now.plusSeconds(1));

        var snapshot = agg.snapshot();
        assertEquals(0, snapshot.buyVolume());
        assertEquals(15, snapshot.sellVolume());
        assertEquals(-15, snapshot.delta());
        assertEquals(0.0, snapshot.buyRatioPct(), 0.1);
    }

    @Test
    void mixedTradesCorrectRatio() {
        var agg = new TickByTickAggregator(Instrument.MNQ);
        var now = Instant.now();
        agg.onTick(100.0, 60, BUY, now);
        agg.onTick(99.0, 40, SELL, now.plusSeconds(1));

        var snapshot = agg.snapshot();
        assertEquals(60, snapshot.buyVolume());
        assertEquals(40, snapshot.sellVolume());
        assertEquals(20, snapshot.delta());
        assertEquals(60.0, snapshot.buyRatioPct(), 0.1);
    }

    @Test
    void bearishDivergence_priceUpDeltaDown() {
        var agg = new TickByTickAggregator(Instrument.MCL);
        var now = Instant.now();
        // Price going up but more sells
        agg.onTick(100.0, 5, BUY, now);
        agg.onTick(101.0, 20, SELL, now.plusSeconds(1));
        agg.onTick(102.0, 10, SELL, now.plusSeconds(2));

        var snapshot = agg.snapshot();
        assertTrue(snapshot.divergenceDetected());
        assertEquals("BEARISH_DIVERGENCE", snapshot.divergenceType());
    }

    @Test
    void bullishDivergence_priceDownDeltaUp() {
        var agg = new TickByTickAggregator(Instrument.MGC);
        var now = Instant.now();
        // Price going down but more buys
        agg.onTick(2000.0, 20, BUY, now);
        agg.onTick(1999.0, 5, SELL, now.plusSeconds(1));
        agg.onTick(1998.0, 10, BUY, now.plusSeconds(2));

        var snapshot = agg.snapshot();
        assertTrue(snapshot.divergenceDetected());
        assertEquals("BULLISH_DIVERGENCE", snapshot.divergenceType());
    }

    @Test
    void windowEvictionRemovesOldTicks() {
        var agg = new TickByTickAggregator(Instrument.MCL, 10); // 10 second window
        var base = Instant.now().minusSeconds(20);

        // Old ticks (will be evicted)
        agg.onTick(100.0, 100, BUY, base);
        // Recent ticks
        agg.onTick(101.0, 5, SELL, Instant.now());

        var snapshot = agg.snapshot();
        // Old 100-volume BUY should be evicted
        assertEquals(0, snapshot.buyVolume());
        assertEquals(5, snapshot.sellVolume());
    }

    @Test
    void unclassifiedTicksIgnored() {
        var agg = new TickByTickAggregator(Instrument.E6);
        var now = Instant.now();
        agg.onTick(1.05, 10, UNCLASSIFIED, now);

        assertFalse(agg.hasData());
    }

    @Test
    void highLowPriceTracking() {
        var agg = new TickByTickAggregator(Instrument.MCL);
        var now = Instant.now();
        agg.onTick(100.0, 5, BUY, now);
        agg.onTick(102.5, 10, SELL, now.plusSeconds(1));
        agg.onTick(99.0, 8, BUY, now.plusSeconds(2));
        agg.onTick(101.0, 3, SELL, now.plusSeconds(3));

        var snapshot = agg.snapshot();
        assertEquals(102.5, snapshot.highPrice(), 0.001);
        assertEquals(99.0, snapshot.lowPrice(), 0.001);
    }

    @Test
    void emptyAggregatorReturnsNaNPrices() {
        var agg = new TickByTickAggregator(Instrument.MCL);
        var snapshot = agg.snapshot();
        assertTrue(Double.isNaN(snapshot.highPrice()));
        assertTrue(Double.isNaN(snapshot.lowPrice()));
    }

    @Test
    void leeReadyClassification() {
        // Trade at ask = BUY
        assertEquals(BUY, IbkrTickDataAdapter.classifyTrade(100.05, 100.00, 100.05));
        // Trade above ask = BUY
        assertEquals(BUY, IbkrTickDataAdapter.classifyTrade(100.10, 100.00, 100.05));
        // Trade at bid = SELL
        assertEquals(SELL, IbkrTickDataAdapter.classifyTrade(100.00, 100.00, 100.05));
        // Trade below bid = SELL
        assertEquals(SELL, IbkrTickDataAdapter.classifyTrade(99.95, 100.00, 100.05));
        // Trade above midpoint = BUY
        assertEquals(BUY, IbkrTickDataAdapter.classifyTrade(100.03, 100.00, 100.05));
        // Trade below midpoint = SELL
        assertEquals(SELL, IbkrTickDataAdapter.classifyTrade(100.02, 100.00, 100.05));
        // Invalid quotes = UNCLASSIFIED
        assertEquals(UNCLASSIFIED, IbkrTickDataAdapter.classifyTrade(100.0, 0, 0));
    }

    @Test
    void snapshotWindow_includesOnlyTicksWithinWindow() {
        // 5-min default window holds all ticks; snapshotWindow(10s) should drop the older ones
        var agg = new TickByTickAggregator(Instrument.MNQ);
        var now = Instant.now();
        // Old tick: 30s ago, BUY 100
        agg.onTick(27000.0, 100, BUY, now.minusSeconds(30));
        // Recent ticks within 10s window
        agg.onTick(27001.0, 50, BUY, now.minusSeconds(5));
        agg.onTick(27002.0, 30, SELL, now.minusSeconds(2));

        var full = agg.snapshot();
        assertEquals(150, full.buyVolume());
        assertEquals(30, full.sellVolume());

        var recent = agg.snapshotWindow(10);
        assertEquals(50, recent.buyVolume(), "old 30s tick should be excluded");
        assertEquals(30, recent.sellVolume());
        assertEquals(20, recent.delta());
    }

    @Test
    void snapshotWindow_emptyWhenNoRecentTicks() {
        var agg = new TickByTickAggregator(Instrument.MNQ);
        var now = Instant.now();
        // Only old ticks
        agg.onTick(27000.0, 100, BUY, now.minusSeconds(120));

        var recent = agg.snapshotWindow(10);
        assertEquals(0, recent.buyVolume());
        assertEquals(0, recent.sellVolume());
        assertEquals(0, recent.delta());
    }

    @Test
    void snapshotWindow_doesNotMutateTrendState() {
        // snapshot() updates previousCumulativeDelta; snapshotWindow() must not — they
        // observe different time horizons and would corrupt each other's trend signal.
        var agg = new TickByTickAggregator(Instrument.MNQ);
        var now = Instant.now();
        agg.onTick(27000.0, 100, BUY, now.minusSeconds(2));
        agg.onTick(27001.0, 50, SELL, now.minusSeconds(1));

        // Establish trend state via snapshot()
        var s1 = agg.snapshot();
        // Now call snapshotWindow several times — should not affect the next snapshot()'s trend
        agg.snapshotWindow(5);
        agg.snapshotWindow(5);

        // The next full snapshot should compute trend relative to s1, not the window snapshots
        var s2 = agg.snapshot();
        assertEquals(s1.delta(), s2.delta(), "delta unchanged when no new ticks");
        // Trend on identical delta should be FLAT (no change vs previous)
        assertEquals("FLAT", s2.deltaTrend());
    }

    // ── L2: tick-rule provenance stamping ───────────────────────────────────

    @Test
    void backCompatOnTickDefaultsToQuoteClassifiedRealTicks() {
        var agg = new TickByTickAggregator(Instrument.MNQ);
        var now = Instant.now();
        agg.onTick(100.0, 10, BUY, now);            // 4-arg overload → tickRule=false
        assertEquals(TickAggregation.SOURCE_REAL_TICKS, agg.snapshot().source());
    }

    @Test
    void allTickRuleVolumeStampsTickRuleSource() {
        var agg = new TickByTickAggregator(Instrument.MNQ); // default min-quote-fraction 0.5
        var now = Instant.now();
        agg.onTick(100.0, 10, BUY, true, now);
        agg.onTick(101.0, 10, BUY, true, now.plusSeconds(1));
        var s = agg.snapshot();
        assertEquals(TickAggregation.SOURCE_REAL_TICKS_TICKRULE, s.source());
        assertEquals(20, s.buyVolume(), "volume is still real even when tick-rule classified");
    }

    @Test
    void majorityQuoteVolumeStampsRealTicks() {
        var agg = new TickByTickAggregator(Instrument.MNQ);
        var now = Instant.now();
        agg.onTick(100.0, 60, BUY, false, now);                 // quote-classified
        agg.onTick(101.0, 40, SELL, true, now.plusSeconds(1));  // tick-rule
        // quote fraction = 60/100 = 0.6 ≥ 0.5 → REAL_TICKS
        assertEquals(TickAggregation.SOURCE_REAL_TICKS, agg.snapshot().source());
    }

    @Test
    void minorityQuoteVolumeStampsTickRule() {
        var agg = new TickByTickAggregator(Instrument.MNQ);
        var now = Instant.now();
        agg.onTick(100.0, 40, BUY, false, now);                 // quote-classified
        agg.onTick(101.0, 60, SELL, true, now.plusSeconds(1));  // tick-rule
        // quote fraction = 40/100 = 0.4 < 0.5 → REAL_TICKS_TICKRULE
        assertEquals(TickAggregation.SOURCE_REAL_TICKS_TICKRULE, agg.snapshot().source());
    }

    @Test
    void quoteFractionAtThresholdStaysRealTicks() {
        var agg = new TickByTickAggregator(Instrument.MNQ);
        var now = Instant.now();
        agg.onTick(100.0, 50, BUY, false, now);                 // quote-classified
        agg.onTick(101.0, 50, SELL, true, now.plusSeconds(1));  // tick-rule
        // quote fraction = 0.5 == threshold → REAL_TICKS (≥ is inclusive)
        assertEquals(TickAggregation.SOURCE_REAL_TICKS, agg.snapshot().source());
    }

    @Test
    void snapshotReadOnly_doesNotEvictTheSharedDeque() {
        // The race fix: snapshotReadOnly() must filter expired ticks via a cutoff, NOT call
        // evictExpired() (whose non-atomic peekFirst/pollFirst races the scheduler). So an expired
        // tick is excluded from the result but REMAINS in the deque (hasData stays true).
        var agg = new TickByTickAggregator(Instrument.MNQ, 10); // 10s window
        var now = Instant.now();
        agg.onTick(100.0, 50, BUY, now.minusSeconds(60)); // expired (older than 10s)

        var snap = agg.snapshotReadOnly();
        assertEquals(0, snap.buyVolume(), "expired tick excluded from the read-only result");
        assertTrue(agg.hasData(), "read-only snapshot must NOT evict — the tick stays in the deque");
    }

    // ── Session-anchored CVD (cumulativeDelta semantics change) ─────────────
    //
    // cumulativeDelta used to be the 5-min window delta relabeled; it now carries the
    // session-anchored CVD (RTH anchor 09:30 ET inside RTH, else Globex-day 17:00 ET).
    // All instants below are FIXED (never wall-clock); boundaries via TradingSessionResolver.

    // Wednesday 2026-06-10, EDT (UTC-4). Globex session = Tue 17:00 ET → Wed 17:00 ET.
    private static final Instant WED_0300_ET = Instant.parse("2026-06-10T07:00:00Z");
    private static final Instant WED_0400_ET = Instant.parse("2026-06-10T08:00:00Z");
    private static final Instant WED_0900_ET = Instant.parse("2026-06-10T13:00:00Z");
    private static final Instant WED_0915_ET = Instant.parse("2026-06-10T13:15:00Z");
    private static final Instant WED_0931_ET = Instant.parse("2026-06-10T13:31:00Z");
    private static final Instant WED_0935_ET = Instant.parse("2026-06-10T13:35:00Z");
    private static final Instant WED_1630_ET = Instant.parse("2026-06-10T20:30:00Z");
    private static final Instant WED_1830_ET = Instant.parse("2026-06-10T22:30:00Z");
    private static final Instant WED_1900_ET = Instant.parse("2026-06-10T23:00:00Z");
    private static final Instant TUE_1700_ET = Instant.parse("2026-06-09T21:00:00Z");
    private static final Instant WED_1700_ET = Instant.parse("2026-06-10T21:00:00Z");
    private static final Instant WED_RTH_OPEN = Instant.parse("2026-06-10T13:30:00Z");

    @Test
    void sessionCvd_accumulatesBeyondTheRollingWindow() {
        // 300s window: the first tick is evicted from the deque by the second (1h later),
        // but the session CVD must keep counting it — it is accumulated, never rebuilt.
        var agg = new TickByTickAggregator(Instrument.MNQ, 300);
        agg.onTick(100.0, 100, BUY, WED_0300_ET);
        agg.onTick(101.0, 30, SELL, WED_0400_ET);

        var cvd = agg.sessionCvd(WED_0400_ET);
        assertEquals(70, cvd.value(), "CVD must survive deque eviction");
        assertEquals("GLOBEX", cvd.anchor());
        assertEquals(TUE_1700_ET, cvd.anchorStart(), "Globex anchor = Tue 17:00 ET in UTC");
    }

    @Test
    void sessionCvd_rthAnchorResetsAtNineThirtyEt() {
        var agg = new TickByTickAggregator(Instrument.MNQ);
        agg.onTick(100.0, 50, BUY, WED_0900_ET);   // pre-RTH → Globex only
        assertEquals(50, agg.sessionCvd(WED_0915_ET).value());
        assertEquals("GLOBEX", agg.sessionCvd(WED_0915_ET).anchor());

        agg.onTick(101.0, 20, BUY, WED_0931_ET);   // first RTH tick
        var rth = agg.sessionCvd(WED_0935_ET);
        assertEquals("RTH", rth.anchor());
        assertEquals(20, rth.value(), "RTH CVD must NOT include the pre-09:30 tick");
        assertEquals(WED_RTH_OPEN, rth.anchorStart());
    }

    @Test
    void sessionCvd_fallsBackToGlobexAfterRthClose() {
        var agg = new TickByTickAggregator(Instrument.MNQ);
        agg.onTick(100.0, 50, BUY, WED_0900_ET);   // pre-RTH
        agg.onTick(101.0, 20, BUY, WED_0931_ET);   // RTH
        // 16:30 ET is past RTH close but inside the same Globex day → Globex CVD = all ticks.
        var cvd = agg.sessionCvd(Instant.parse("2026-06-10T20:30:00Z"));
        assertEquals("GLOBEX", cvd.anchor());
        assertEquals(70, cvd.value(), "Globex CVD accumulates RTH ticks too");
    }

    @Test
    void sessionCvd_globexResetsAtDailySessionRollover() {
        var agg = new TickByTickAggregator(Instrument.MNQ);
        agg.onTick(100.0, 40, BUY, WED_1630_ET);   // old Globex day (Tue 17:00 → Wed 17:00)
        agg.onTick(101.0, 10, SELL, WED_1830_ET);  // new Globex day (Wed 17:00 → Thu 17:00)

        var cvd = agg.sessionCvd(WED_1900_ET);
        assertEquals(-10, cvd.value(), "17:00 ET rollover must reset the Globex CVD");
        assertEquals(WED_1700_ET, cvd.anchorStart());
    }

    @Test
    void sessionCvd_readsZeroWhenNoTickSinceSessionBoundary() {
        var agg = new TickByTickAggregator(Instrument.MNQ);
        agg.onTick(100.0, 100, BUY, WED_0300_ET);
        // Read on Thursday 03:00 ET: the stored anchor is stale → 0, never the old session's CVD.
        var cvd = agg.sessionCvd(Instant.parse("2026-06-11T07:00:00Z"));
        assertEquals(0, cvd.value());
    }

    @Test
    void sessionCvd_dstSpringForward_rthAnchorIsDstAware() {
        // Monday 2026-03-09 — first weekday after the US spring-forward (2026-03-08).
        // EDT (UTC-4): 09:31 ET = 13:31Z; RTH anchor must be 13:30Z, not the EST 14:30Z.
        var agg = new TickByTickAggregator(Instrument.MNQ);
        agg.onTick(100.0, 10, BUY, Instant.parse("2026-03-09T13:31:00Z"));
        var cvd = agg.sessionCvd(Instant.parse("2026-03-09T13:35:00Z"));
        assertEquals("RTH", cvd.anchor());
        assertEquals(10, cvd.value());
        assertEquals(Instant.parse("2026-03-09T13:30:00Z"), cvd.anchorStart());
    }

    @Test
    void sessionCvd_dstFallBack_rthAnchorIsDstAware() {
        // Monday 2026-11-02 — first weekday after the US fall-back (2026-11-01).
        // EST (UTC-5): 09:31 ET = 14:31Z; RTH anchor must be 14:30Z.
        var agg = new TickByTickAggregator(Instrument.MNQ);
        agg.onTick(100.0, 10, BUY, Instant.parse("2026-11-02T14:31:00Z"));
        var cvd = agg.sessionCvd(Instant.parse("2026-11-02T14:35:00Z"));
        assertEquals("RTH", cvd.anchor());
        assertEquals(10, cvd.value());
        assertEquals(Instant.parse("2026-11-02T14:30:00Z"), cvd.anchorStart());
    }

    @Test
    void snapshotCumulativeDeltaCarriesTheSessionCvd() {
        // The aggregation's cumulativeDelta must equal sessionCvd(now), NOT the window delta.
        var agg = new TickByTickAggregator(Instrument.MNQ, 10); // 10s window
        var now = Instant.now();
        agg.onTick(27000.0, 100, BUY, now.minusSeconds(60));  // outside the window
        agg.onTick(27001.0, 5, SELL, now);                    // inside the window

        var snapshot = agg.snapshot();
        assertEquals(-5, snapshot.delta(), "window delta excludes the evicted tick");
        assertEquals(agg.sessionCvd(Instant.now()).value(), snapshot.cumulativeDelta(),
            "cumulativeDelta = session CVD, no longer the window delta relabeled");
    }

    // ── Speed of tape ────────────────────────────────────────────────────────

    @Test
    void tapeSpeed_countsTradesAndContractsPerWindow() {
        var agg = new TickByTickAggregator(Instrument.MNQ);
        var now = WED_0400_ET;
        agg.onTick(100.0, 10, BUY, now.minusSeconds(40)); // outside 5s and 30s
        agg.onTick(100.0, 4, BUY, now.minusSeconds(20));  // 30s only
        agg.onTick(100.0, 2, SELL, now.minusSeconds(3));  // both
        agg.onTick(100.0, 1, BUY, now.minusSeconds(1));   // both

        var t5 = agg.tapeSpeed(5, now);
        assertEquals(2, t5.trades());
        assertEquals(3, t5.contracts());
        assertEquals(0.4, t5.tradesPerSec(), 1e-9);
        assertEquals(0.6, t5.contractsPerSec(), 1e-9);

        var t30 = agg.tapeSpeed(30, now);
        assertEquals(3, t30.trades());
        assertEquals(7, t30.contracts());
        assertEquals(0.1, t30.tradesPerSec(), 1e-9);
    }

    @Test
    void tapeSpeed_emptyWindowIsZero() {
        var agg = new TickByTickAggregator(Instrument.MNQ);
        agg.onTick(100.0, 10, BUY, WED_0300_ET);
        var t5 = agg.tapeSpeed(5, WED_0400_ET); // an hour later
        assertEquals(0, t5.trades());
        assertEquals(0.0, t5.tradesPerSec(), 1e-9);
    }

    @Test
    void snapshotReadOnly_doesNotMutateTrendState() {
        // snapshotReadOnly() is called off the scheduler thread (status endpoint); it must NOT
        // touch previousCumulativeDelta or it would race + corrupt the scheduler's deltaTrend.
        var agg = new TickByTickAggregator(Instrument.MNQ);
        var now = Instant.now();
        agg.onTick(27000.0, 100, BUY, now.minusSeconds(2));
        agg.onTick(27001.0, 50, SELL, now.minusSeconds(1));

        var s1 = agg.snapshot();          // establishes trend baseline
        agg.snapshotReadOnly();           // must be a no-op for trend state
        agg.snapshotReadOnly();
        var s2 = agg.snapshot();

        assertEquals(s1.delta(), s2.delta(), "delta unchanged when no new ticks");
        assertEquals("FLAT", s2.deltaTrend(), "read-only snapshots must not shift the trend baseline");
    }
}
