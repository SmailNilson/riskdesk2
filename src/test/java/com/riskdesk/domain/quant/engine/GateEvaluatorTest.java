package com.riskdesk.domain.quant.engine;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.DistEntry;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.MarketSnapshot;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.model.QuantState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural specification of the 7-gate SHORT-setup evaluator. The cases
 * cover the critical paths that diverge between v2 and v3 of the Python
 * reference implementation, including the G2 dist/accu separation fix.
 */
class GateEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-04-29T18:00:00Z"); // 14:00 ET
    private static final LocalDate SESSION = LocalDate.of(2026, 4, 29);

    private final GateEvaluator evaluator = new GateEvaluator();

    // ── Helpers ────────────────────────────────────────────────────────────

    private QuantState baseState() {
        return QuantState.reset(SESSION).withMonitorStartPx(20_000.0);
    }

    private QuantState withDistHistory(QuantState s, int... confs) {
        QuantState out = s;
        for (int c : confs) {
            out = out.appendDistOnly(new DistEntry(DistEntry.DIST, c, NOW));
        }
        return out;
    }

    private MarketSnapshot.Builder snap() {
        return new MarketSnapshot.Builder();
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Setup 7/7 valid: delta -315, buy% 41, DIST 87×3, ABS BEAR n8=10 → score 7")
    void scoreFullSetup_returnsSeven() {
        QuantState state = withDistHistory(baseState(), 87, 87, 87);
        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(-315.0).buyPct(41.0)
            .absFresh(10).absBull8(0).absBear8(10).absMaxScore(9.5)
            .dist("DISTRIBUTION", 87).cycleAge(2)
            .build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.score()).isEqualTo(7);
        assertThat(snapOut.isShortSetup7_7()).isTrue();
        // All 7 SHORT gates pass on a strong-bear snapshot.
        for (Gate g : new Gate[] {Gate.G0_REGIME, Gate.G1_ABS_BEAR, Gate.G2_DIST_PUR,
                Gate.G3_DELTA_NEG, Gate.G4_BUY_PCT_LOW, Gate.G5_ACCU_THRESHOLD, Gate.G6_LIVE_PUSH}) {
            assertThat(snapOut.gates().get(g).ok()).as("SHORT gate %s", g).isTrue();
        }
        assertThat(snapOut.suggestedSL()).isEqualTo(20_025.0);
        assertThat(snapOut.suggestedTP1()).isEqualTo(19_960.0);
        assertThat(snapOut.suggestedTP2()).isEqualTo(19_920.0);
    }

    @Test
    @DisplayName("Trap absorption: delta -200 but price rallied +90pts → G0 KO, 6/7 max")
    void absorptionBullishTrap_blocksRegime() {
        QuantState state = withDistHistory(
            QuantState.reset(SESSION).withMonitorStartPx(19_900.0),
            65, 65, 65);
        MarketSnapshot ms = snap()
            .now(NOW).price(19_990.0).priceSource("LIVE_PUSH")
            .delta(-200.0).buyPct(42.0)
            .absFresh(9).absBull8(0).absBear8(9).absMaxScore(8.5)
            .dist("DISTRIBUTION", 65).build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.gates().get(Gate.G0_REGIME).ok()).isFalse();
        assertThat(snapOut.gates().get(Gate.G0_REGIME).reason()).contains("HAUSSIER");
        assertThat(snapOut.score()).isLessThanOrEqualTo(6);
    }

    @Test
    @DisplayName("G5 conditional threshold: delta -600 + buy% 40 → ACCU seuil 75 (not 50)")
    void g5Threshold_extremeBearishSignal_requires75() {
        QuantState state = withDistHistory(baseState(), 70, 70);
        // ACCU at 60% would block at standard threshold 50, but here delta is extreme
        // so the threshold is bumped to 75 — this ACCU @60 should NOT block.
        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(-600.0).buyPct(40.0)
            .absFresh(10).absBull8(0).absBear8(10).absMaxScore(9.0)
            .dist("ACCUMULATION", 60)
            .build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.gates().get(Gate.G5_ACCU_THRESHOLD).ok()).isTrue();
        assertThat(snapOut.gates().get(Gate.G5_ACCU_THRESHOLD).reason()).contains("75");
    }

    @Test
    @DisplayName("G5 conditional threshold: ACCU at 80 with extreme bearish → BLOCKS even at threshold 75")
    void g5Threshold_extremeBearishButHighAccu_blocks() {
        QuantState state = withDistHistory(baseState(), 70, 70);
        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(-600.0).buyPct(40.0)
            .absFresh(10).absBull8(0).absBear8(10).absMaxScore(9.0)
            .dist("ACCUMULATION", 80).build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.gates().get(Gate.G5_ACCU_THRESHOLD).ok()).isFalse();
        assertThat(snapOut.gates().get(Gate.G5_ACCU_THRESHOLD).reason()).contains("BLOQUE");
    }

    @Test
    @DisplayName("G2 separation (Option B): one ACCU does not contaminate dist_only_history")
    void g2Separation_accuDoesNotContaminate() {
        // Two clean DIST scans @ 70, then one ACCU @ 60 (which is what evaluator should
        // route into accu_only_history — NOT into dist_only_history).
        // After the evaluator processes this snapshot, dist_only_history must still
        // contain only DIST entries → 2/3 ≥ 60 still satisfied.
        QuantState state = withDistHistory(baseState(), 70, 70);
        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(-150.0).buyPct(45.0)
            .absFresh(10).absBull8(0).absBear8(10).absMaxScore(9.0)
            .dist("ACCUMULATION", 60).distTimestamp(NOW.minusSeconds(60)).build();

        GateEvaluator.Outcome outcome = evaluator.evaluate(ms, state, Instrument.MNQ);

        assertThat(outcome.snapshot().gates().get(Gate.G2_DIST_PUR).ok()).isTrue();
        // The new state must have routed the ACCU into the accu list, not the dist list.
        assertThat(outcome.nextState().distOnlyHistory())
            .extracting(DistEntry::type)
            .doesNotContain(DistEntry.ACCU);
        assertThat(outcome.nextState().accuOnlyHistory())
            .extracting(DistEntry::type)
            .containsExactly(DistEntry.ACCU);
    }

    @Test
    @DisplayName("G0 regime: 3 ABS BULL scans within 30 minutes blocks SHORT bias")
    void g0Regime_threeRecentBullScans_blocks() {
        QuantState state = baseState()
            .appendAbsBullAndPrune(NOW.minusSeconds(60), NOW)
            .appendAbsBullAndPrune(NOW.minusSeconds(120), NOW)
            .appendAbsBullAndPrune(NOW.minusSeconds(180), NOW);

        MarketSnapshot ms = snap()
            .now(NOW).price(20_010.0).priceSource("LIVE_PUSH")
            .delta(-300.0).buyPct(40.0)
            .absFresh(8).absBull8(0).absBear8(8).absMaxScore(8.0)
            .dist("DISTRIBUTION", 70).build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.gates().get(Gate.G0_REGIME).ok()).isFalse();
        assertThat(snapOut.gates().get(Gate.G0_REGIME).reason()).contains("ABS BULL");
    }

    @Test
    @DisplayName("G1 incoherence: delta > +500 invalidates BEAR-dominant absorption")
    void g1AbsBear_strongPositiveDelta_isIncoherent() {
        QuantState state = withDistHistory(baseState(), 80, 80, 80);
        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(700.0).buyPct(45.0)
            .absFresh(10).absBull8(0).absBear8(10).absMaxScore(9.0)
            .dist("DISTRIBUTION", 80).build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.gates().get(Gate.G1_ABS_BEAR).ok()).isFalse();
        assertThat(snapOut.gates().get(Gate.G1_ABS_BEAR).reason()).contains("incohérent");
    }

    @Test
    @DisplayName("Session reset: a new ET trading day wipes all history")
    void sessionReset_newDay_clearsState() {
        QuantState yesterday = QuantState.reset(LocalDate.of(2026, 4, 28))
            .withMonitorStartPx(19_500.0)
            .appendDelta(-200.0)
            .appendDistOnly(new DistEntry(DistEntry.DIST, 80, NOW.minusSeconds(3600)))
            .appendAbsBullAndPrune(NOW.minusSeconds(60), NOW.minusSeconds(60));

        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(-100.0).buyPct(50.0)
            .absFresh(0).absBull8(0).absBear8(0).absMaxScore(0)
            .dist(null, null).build();

        GateEvaluator.Outcome outcome = evaluator.evaluate(ms, yesterday, Instrument.MNQ);

        assertThat(outcome.nextState().sessionDate()).isEqualTo(LocalDate.of(2026, 4, 29));
        assertThat(outcome.nextState().deltaHistory()).hasSize(1).containsExactly(-100.0);
        assertThat(outcome.nextState().distOnlyHistory()).isEmpty();
        // monitor start price is captured fresh for the new session
        assertThat(outcome.nextState().monitorStartPx()).isEqualTo(20_000.0);
    }

    @Test
    @DisplayName("G3 trend bonus: 3 strictly decreasing deltas with last <-100 emits +TREND tag")
    void g3DeltaTrend_strictlyDecreasing_addsTrendBonus() {
        // The state already has 2 deltas (-50, -120); the new snapshot adds -200,
        // producing a strictly decreasing trend (-50 > -120 > -200) with last < -100.
        QuantState state = baseState()
            .appendDelta(-50.0)
            .appendDelta(-120.0);

        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(-200.0).buyPct(43.0)
            .absFresh(10).absBull8(0).absBear8(10).absMaxScore(9.0)
            .dist("DISTRIBUTION", 80).build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, withDistHistory(state, 70, 70), Instrument.MNQ).snapshot();

        assertThat(snapOut.gates().get(Gate.G3_DELTA_NEG).ok()).isTrue();
        assertThat(snapOut.gates().get(Gate.G3_DELTA_NEG).reason()).contains("+TREND");
    }

    @Test
    @DisplayName("G6 LIVE_PUSH: stale source fails the gate even when other 6 are green")
    void g6LivePush_stalePriceSource_fails() {
        QuantState state = withDistHistory(baseState(), 80, 80, 80);
        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("DB_FALLBACK")
            .delta(-300.0).buyPct(40.0)
            .absFresh(10).absBull8(0).absBear8(10).absMaxScore(9.0)
            .dist("DISTRIBUTION", 80).build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.gates().get(Gate.G6_LIVE_PUSH).ok()).isFalse();
        assertThat(snapOut.score()).isEqualTo(6);
        assertThat(snapOut.isShortAlert6_7()).isTrue();
    }

    @Test
    @DisplayName("G2 not yet enough history: empty + 1 DIST → 1/3, fails")
    void g2NotEnoughHistory_fails() {
        QuantState state = baseState(); // empty dist history
        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(-200.0).buyPct(40.0)
            .absFresh(10).absBull8(0).absBear8(10).absMaxScore(9.0)
            .dist("DISTRIBUTION", 80).distTimestamp(NOW.minusSeconds(60)).build();

        GateEvaluator.Outcome outcome = evaluator.evaluate(ms, state, Instrument.MNQ);

        assertThat(outcome.snapshot().gates().get(Gate.G2_DIST_PUR).ok()).isFalse();
        assertThat(outcome.nextState().distOnlyHistory()).hasSize(1);
    }

    @Test
    @DisplayName("G2 confidence below 60 does not count toward persistence")
    void g2LowConfidence_doesNotCount() {
        // 3 DIST scans but all below 60% → none counted → fails
        QuantState state = withDistHistory(baseState(), 50, 55, 58);
        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(-200.0).buyPct(40.0)
            .absFresh(10).absBull8(0).absBear8(10).absMaxScore(9.0)
            .dist("DISTRIBUTION", 55).build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.gates().get(Gate.G2_DIST_PUR).ok()).isFalse();
    }

    @Test
    @DisplayName("Empty market snapshot: no quote / no flow → low score, no exception")
    void emptySnapshot_doesNotThrow_andScoresLow() {
        MarketSnapshot empty = snap()
            .now(NOW).price(null).priceSource("")
            .delta(null).buyPct(null)
            .absFresh(0).absBull8(0).absBear8(0).absMaxScore(0)
            .dist(null, null).build();

        QuantSnapshot snapOut = evaluator.evaluate(empty, baseState(), Instrument.MNQ).snapshot();

        assertThat(snapOut.score()).isLessThanOrEqualTo(2);
        // Gate map carries both SHORT (G0–G6) and LONG (L0–L6) tracks.
        assertThat(snapOut.gates()).hasSize(14);
        assertThat(snapOut.longScore()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Dist event dedupe: same DIST event seen on N consecutive scans appends only once")
    void distHistory_dedupesByTimestamp() {
        java.time.Instant eventTs = NOW.minusSeconds(120);
        MarketSnapshot.Builder base = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(-200.0).buyPct(45.0)
            .absFresh(10).absBull8(0).absBear8(10).absMaxScore(9.0);
        MarketSnapshot tick = base.dist("DISTRIBUTION", 80).distTimestamp(eventTs).build();

        QuantState state = baseState();
        for (int i = 0; i < 5; i++) {
            state = evaluator.evaluate(tick, state, Instrument.MNQ).nextState();
        }

        assertThat(state.distOnlyHistory()).hasSize(1);
        QuantSnapshot snapOut = evaluator.evaluate(tick, state, Instrument.MNQ).snapshot();
        assertThat(snapOut.gates().get(Gate.G2_DIST_PUR).ok()).isFalse();
    }

    // ── LONG mirror tests (LONG-symmetry slice) ─────────────────────────

    @Test
    @DisplayName("LONG full setup: delta +315, buy% 59, ACCU 87×3, ABS BULL n8=10 → LONG score 7")
    void scoreFullLongSetup_returnsSeven() {
        QuantState state = QuantState.reset(SESSION).withMonitorStartPx(20_000.0)
            .appendAccuOnly(new DistEntry(DistEntry.ACCU, 87, NOW.minusSeconds(300)))
            .appendAccuOnly(new DistEntry(DistEntry.ACCU, 87, NOW.minusSeconds(180)))
            .appendAccuOnly(new DistEntry(DistEntry.ACCU, 87, NOW.minusSeconds(60)));
        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(315.0).buyPct(59.0)
            .absFresh(10).absBull8(10).absBear8(0).absMaxScore(9.5)
            .dist("ACCUMULATION", 87).cycleAge(2)
            .build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.longScore()).isEqualTo(7);
        assertThat(snapOut.isLongSetup7_7()).isTrue();
        for (Gate g : new Gate[] {Gate.L0_REGIME, Gate.L1_ABS_BULL, Gate.L2_ACCU_PUR,
                Gate.L3_DELTA_POS, Gate.L4_BUY_PCT_HIGH, Gate.L5_DIST_THRESHOLD, Gate.L6_LIVE_PUSH}) {
            assertThat(snapOut.gates().get(g).ok()).as("LONG gate %s", g).isTrue();
        }
        assertThat(snapOut.suggestedSL_LONG()).isEqualTo(19_975.0);
        assertThat(snapOut.suggestedTP1_LONG()).isEqualTo(20_040.0);
        assertThat(snapOut.suggestedTP2_LONG()).isEqualTo(20_080.0);
    }

    @Test
    @DisplayName("L0 LONG regime: bearish day move beyond -75pts blocks LONG")
    void l0Regime_bearishDay_blocks() {
        QuantState state = QuantState.reset(SESSION).withMonitorStartPx(20_100.0);
        MarketSnapshot ms = snap()
            .now(NOW).price(20_010.0).priceSource("LIVE_PUSH")  // -90pts
            .delta(200.0).buyPct(53.0)
            .absFresh(8).absBull8(8).absBear8(0).absMaxScore(8.5)
            .dist("ACCUMULATION", 65).build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.gates().get(Gate.L0_REGIME).ok()).isFalse();
        assertThat(snapOut.gates().get(Gate.L0_REGIME).reason()).contains("BAISSIER");
    }

    @Test
    @DisplayName("L0 LONG regime: 3 ABS BEAR scans in 30min blocks LONG")
    void l0Regime_threeRecentBearScans_blocks() {
        QuantState state = QuantState.reset(SESSION).withMonitorStartPx(20_000.0)
            .appendAbsBearAndPrune(NOW.minusSeconds(60), NOW)
            .appendAbsBearAndPrune(NOW.minusSeconds(120), NOW)
            .appendAbsBearAndPrune(NOW.minusSeconds(180), NOW);

        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(300.0).buyPct(60.0)
            .absFresh(8).absBull8(8).absBear8(0).absMaxScore(8.0)
            .dist("ACCUMULATION", 70).build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.gates().get(Gate.L0_REGIME).ok()).isFalse();
        assertThat(snapOut.gates().get(Gate.L0_REGIME).reason()).contains("ABS BEAR");
    }

    @Test
    @DisplayName("L1 incoherence: delta < -500 invalidates BULL-dominant absorption")
    void l1AbsBull_strongNegativeDelta_isIncoherent() {
        QuantState state = QuantState.reset(SESSION).withMonitorStartPx(20_000.0);
        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(-700.0).buyPct(55.0)
            .absFresh(10).absBull8(10).absBear8(0).absMaxScore(9.0)
            .dist("ACCUMULATION", 80).build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.gates().get(Gate.L1_ABS_BULL).ok()).isFalse();
        assertThat(snapOut.gates().get(Gate.L1_ABS_BULL).reason()).contains("incohérent");
    }

    @Test
    @DisplayName("L2 ACCU persistence: 2/3 ACCU ≥ 60% passes, dist does not contaminate")
    void l2AccuPersistence_passesWithDistRouting() {
        QuantState state = QuantState.reset(SESSION).withMonitorStartPx(20_000.0)
            .appendAccuOnly(new DistEntry(DistEntry.ACCU, 70, NOW.minusSeconds(300)))
            .appendAccuOnly(new DistEntry(DistEntry.ACCU, 75, NOW.minusSeconds(180)));
        // Feed a DIST event — must NOT be routed into accuOnlyHistory.
        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(150.0).buyPct(54.0)
            .absFresh(10).absBull8(10).absBear8(0).absMaxScore(9.0)
            .dist("DISTRIBUTION", 60).distTimestamp(NOW.minusSeconds(60)).build();

        GateEvaluator.Outcome outcome = evaluator.evaluate(ms, state, Instrument.MNQ);

        assertThat(outcome.snapshot().gates().get(Gate.L2_ACCU_PUR).ok()).isTrue();
        assertThat(outcome.nextState().accuOnlyHistory())
            .extracting(DistEntry::type)
            .doesNotContain(DistEntry.DIST);
    }

    @Test
    @DisplayName("L3 trend bonus: 3 strictly increasing deltas with last > +100 emits +TREND tag")
    void l3DeltaTrend_strictlyIncreasing_addsTrendBonus() {
        QuantState state = QuantState.reset(SESSION).withMonitorStartPx(20_000.0)
            .appendDelta(50.0)
            .appendDelta(120.0);

        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(200.0).buyPct(57.0)
            .absFresh(10).absBull8(10).absBear8(0).absMaxScore(9.0)
            .dist("ACCUMULATION", 80).build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.gates().get(Gate.L3_DELTA_POS).ok()).isTrue();
        assertThat(snapOut.gates().get(Gate.L3_DELTA_POS).reason()).contains("+TREND");
    }

    @Test
    @DisplayName("L4 buy% boundary: 52% strict — 52.0 fails, 52.1 passes")
    void l4BuyPctBoundary_strict() {
        QuantState state = QuantState.reset(SESSION).withMonitorStartPx(20_000.0);
        MarketSnapshot at52 = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(150.0).buyPct(52.0)
            .absFresh(10).absBull8(10).absBear8(0).absMaxScore(9.0)
            .dist("ACCUMULATION", 60).build();
        QuantSnapshot at52Out = evaluator.evaluate(at52, state, Instrument.MNQ).snapshot();
        assertThat(at52Out.gates().get(Gate.L4_BUY_PCT_HIGH).ok()).isFalse();

        MarketSnapshot at53 = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(150.0).buyPct(53.0)
            .absFresh(10).absBull8(10).absBear8(0).absMaxScore(9.0)
            .dist("ACCUMULATION", 60).build();
        QuantSnapshot at53Out = evaluator.evaluate(at53, state, Instrument.MNQ).snapshot();
        assertThat(at53Out.gates().get(Gate.L4_BUY_PCT_HIGH).ok()).isTrue();
    }

    @Test
    @DisplayName("L5 conditional DIST threshold: delta +600 + buy% 60 → seuil 75 (DIST @60 PASS)")
    void l5DistThreshold_extremeBullishSignal_requires75() {
        QuantState state = QuantState.reset(SESSION).withMonitorStartPx(20_000.0);
        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(600.0).buyPct(60.0)
            .absFresh(10).absBull8(10).absBear8(0).absMaxScore(9.0)
            .dist("DISTRIBUTION", 60).build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.gates().get(Gate.L5_DIST_THRESHOLD).ok()).isTrue();
        assertThat(snapOut.gates().get(Gate.L5_DIST_THRESHOLD).reason()).contains("75");
    }

    @Test
    @DisplayName("L5 DIST blocks LONG when DIST @80 with extreme bullish signal — threshold 75")
    void l5DistThreshold_extremeBullishButHighDist_blocks() {
        QuantState state = QuantState.reset(SESSION).withMonitorStartPx(20_000.0);
        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(600.0).buyPct(60.0)
            .absFresh(10).absBull8(10).absBear8(0).absMaxScore(9.0)
            .dist("DISTRIBUTION", 80).build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.gates().get(Gate.L5_DIST_THRESHOLD).ok()).isFalse();
        assertThat(snapOut.gates().get(Gate.L5_DIST_THRESHOLD).reason()).contains("BLOQUE");
    }

    @Test
    @DisplayName("Both directions independent: bullish snap → high LONG score, low SHORT score")
    void bullishSnap_longScoreHigh_shortScoreLow() {
        QuantState state = QuantState.reset(SESSION).withMonitorStartPx(20_000.0)
            .appendAccuOnly(new DistEntry(DistEntry.ACCU, 80, NOW.minusSeconds(300)))
            .appendAccuOnly(new DistEntry(DistEntry.ACCU, 80, NOW.minusSeconds(60)));
        MarketSnapshot ms = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(250.0).buyPct(58.0)
            .absFresh(10).absBull8(10).absBear8(0).absMaxScore(9.0)
            .dist("ACCUMULATION", 60).build();

        QuantSnapshot snapOut = evaluator.evaluate(ms, state, Instrument.MNQ).snapshot();

        assertThat(snapOut.longScore()).isGreaterThanOrEqualTo(6);
        // SHORT side fails: not BEAR-dominant, delta positive, buy% > 48 etc.
        assertThat(snapOut.score()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Dist event dedupe: distinct timestamps each get their own history entry")
    void distHistory_appendsDistinctTimestamps() {
        QuantState state = baseState();
        java.time.Instant t1 = NOW.minusSeconds(300);
        java.time.Instant t2 = NOW.minusSeconds(180);
        java.time.Instant t3 = NOW.minusSeconds(60);

        MarketSnapshot.Builder base = snap()
            .now(NOW).price(20_000.0).priceSource("LIVE_PUSH")
            .delta(-200.0).buyPct(45.0)
            .absFresh(10).absBull8(0).absBear8(10).absMaxScore(9.0);

        for (int i = 0; i < 2; i++) state = evaluator.evaluate(base.dist("DISTRIBUTION", 75).distTimestamp(t1).build(), state, Instrument.MNQ).nextState();
        for (int i = 0; i < 2; i++) state = evaluator.evaluate(base.dist("DISTRIBUTION", 80).distTimestamp(t2).build(), state, Instrument.MNQ).nextState();
        for (int i = 0; i < 2; i++) state = evaluator.evaluate(base.dist("DISTRIBUTION", 85).distTimestamp(t3).build(), state, Instrument.MNQ).nextState();

        assertThat(state.distOnlyHistory()).hasSize(3);
        assertThat(state.distOnlyHistory()).extracting(DistEntry::ts)
            .containsExactly(t1, t2, t3);
    }
}
