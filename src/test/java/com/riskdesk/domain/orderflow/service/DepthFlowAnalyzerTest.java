package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DepthFlowMetrics;
import com.riskdesk.domain.orderflow.model.DepthLevel;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-domain tests for {@link DepthFlowAnalyzer} — all snapshots use pinned
 * instants, no wall-clock dependence. Covers the OFI event sign convention
 * (Cont-Kukanov-Stoikov), queue-imbalance smoothing + min-mass gate, micro-price
 * offset, the vacuum state machine (3s persistence + reset-on-gap), pull/stack
 * aggregation with the noise floor, and the stale-gap reset.
 */
class DepthFlowAnalyzerTest {

    private static final double TICK = 0.25; // MNQ
    private static final Instant T0 = Instant.parse("2026-06-10T14:30:00Z");
    private static final double EPS = 1e-9;

    private DepthFlowAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new DepthFlowAnalyzer(Instrument.MNQ, TICK, new DepthFlowAnalyzer.Config(
            /* staleGapSeconds */ 10.0,
            /* minQueueMass */ 10,
            /* imbalanceEmaSeconds */ 3.0,
            /* noiseFloorContracts */ 2,
            /* vacuumDepletionRatio */ 0.4,
            /* vacuumHoldRatio */ 0.7,
            /* thinRatio */ 0.5,
            /* vacuumPersistenceSeconds */ 3.0,
            /* ofiZFlagThreshold */ 2.0,
            /* baselineWindowSeconds */ 300.0));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static DepthLevel lvl(double price, long size) {
        return new DepthLevel(price, size, false);
    }

    private static DepthMetrics book(List<DepthLevel> bids, List<DepthLevel> asks,
                                     long totalBid, long totalAsk, Instant ts) {
        double bestBid = bids.isEmpty() ? 0 : bids.get(0).price();
        double bestAsk = asks.isEmpty() ? 0 : asks.get(0).price();
        return new DepthMetrics(Instrument.MNQ, totalBid, totalAsk, 0.0,
            bestBid, bestAsk, bestAsk - bestBid, (bestAsk - bestBid) / TICK,
            null, null, bids, asks, ts);
    }

    /** Single-level book — enough for OFI / imbalance / micro-price cases. */
    private static DepthMetrics simpleBook(double bb, long qb, double ba, long qa, Instant ts) {
        return book(List.of(lvl(bb, qb)), List.of(lvl(ba, qa)), 100, 100, ts);
    }

    private DepthFlowMetrics feed(DepthMetrics snapshot, Instant now) {
        Optional<DepthFlowMetrics> out = analyzer.onSnapshot(snapshot, now);
        assertThat(out).isPresent();
        return out.get();
    }

    // ── Priming ──────────────────────────────────────────────────────────────

    @Test
    void firstSnapshotPrimes_emitsNothing() {
        assertThat(analyzer.onSnapshot(simpleBook(21000.00, 10, 21000.50, 10, T0), T0)).isEmpty();
        // second snapshot produces the first transition
        assertThat(analyzer.onSnapshot(simpleBook(21000.00, 10, 21000.50, 10, T0), T0.plusMillis(500)))
            .isPresent();
    }

    // ── OFI sign convention (Cont-Kukanov-Stoikov) ──────────────────────────

    @Test
    void ofi_bidPriceImproves_isPositive() {
        analyzer.onSnapshot(simpleBook(21000.00, 10, 21000.50, 10, T0), T0);
        // bid steps up one tick with 8 resting; ask untouched
        DepthFlowMetrics m = feed(simpleBook(21000.25, 8, 21000.50, 10, T0), T0.plusMillis(500));

        // Pb up → +qb_curr; ask unchanged → -qa_curr + qa_prev = 0
        assertThat(m.ofi1s()).isEqualTo(8.0);
        assertThat(m.ofi10s()).isEqualTo(8.0);
    }

    @Test
    void ofi_askPriceDrops_isNegative() {
        analyzer.onSnapshot(simpleBook(21000.00, 10, 21000.50, 10, T0), T0);
        // ask steps down one tick with 7 resting; bid untouched
        DepthFlowMetrics m = feed(simpleBook(21000.00, 10, 21000.25, 7, T0), T0.plusMillis(500));

        // Pa down → -qa_curr = -7; bid unchanged → qb_curr - qb_prev = 0
        assertThat(m.ofi1s()).isEqualTo(-7.0);
        assertThat(m.ofi10s()).isEqualTo(-7.0);
    }

    @Test
    void ofi_mixedSizeChangesAtSamePrices_netOut() {
        analyzer.onSnapshot(simpleBook(21000.00, 10, 21000.50, 10, T0), T0);
        // same prices: bid queue +5 (bullish), ask queue +4 (bearish)
        DepthFlowMetrics m = feed(simpleBook(21000.00, 15, 21000.50, 14, T0), T0.plusMillis(500));

        // e = (qb_c - qb_p) + (qa_p - qa_c) = +5 - 4 = +1
        assertThat(m.ofi1s()).isEqualTo(1.0);
    }

    @Test
    void ofi_1sWindowForgets_10sWindowRetains() {
        analyzer.onSnapshot(simpleBook(21000.00, 10, 21000.50, 10, T0), T0);
        feed(simpleBook(21000.25, 8, 21000.50, 10, T0), T0.plusMillis(500)); // e = +8

        // then nothing changes for 2s — the +8 event leaves the 1s window, stays in the 10s one
        DepthFlowMetrics m = null;
        for (long ms = 1000; ms <= 2500; ms += 500) {
            m = feed(simpleBook(21000.25, 8, 21000.50, 10, T0), T0.plusMillis(ms));
        }
        assertThat(m.ofi1s()).isEqualTo(0.0);
        assertThat(m.ofi10s()).isEqualTo(8.0);
    }

    // ── Queue imbalance + micro-price ────────────────────────────────────────

    @Test
    void queueImbalance_initializesToRaw_thenSmoothsTowardNewValue() {
        analyzer.onSnapshot(simpleBook(21000.00, 30, 21000.25, 10, T0), T0);
        // raw I = (30-10)/40 = +0.5 — first transition initializes the EMA
        DepthFlowMetrics first = feed(simpleBook(21000.00, 30, 21000.25, 10, T0), T0.plusMillis(500));
        assertThat(first.queueImbalance()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(EPS));

        // flip to raw I = -0.5: with tau=3s and dt=0.5s the EMA must move but not jump
        DepthFlowMetrics flipped = feed(simpleBook(21000.00, 10, 21000.25, 30, T0), T0.plusMillis(1000));
        assertThat(flipped.queueImbalance()).isLessThan(0.5);
        assertThat(flipped.queueImbalance()).isGreaterThan(0.0); // alpha ≈ 0.15 → ≈ +0.35
    }

    @Test
    void queueImbalance_belowMinQueueMass_isFlaggedNotMeaningful() {
        analyzer.onSnapshot(simpleBook(21000.00, 3, 21000.25, 4, T0), T0);
        DepthFlowMetrics thin = feed(simpleBook(21000.00, 3, 21000.25, 4, T0), T0.plusMillis(500));
        assertThat(thin.queueImbalanceValid()).isFalse(); // mass 7 < 10

        DepthFlowMetrics thick = feed(simpleBook(21000.00, 30, 21000.25, 10, T0), T0.plusMillis(1000));
        assertThat(thick.queueImbalanceValid()).isTrue(); // mass 40 >= 10
    }

    @Test
    void microPrice_leansTowardTheHeavySide() {
        analyzer.onSnapshot(simpleBook(21000.00, 30, 21000.25, 10, T0), T0);
        DepthFlowMetrics m = feed(simpleBook(21000.00, 30, 21000.25, 10, T0), T0.plusMillis(500));

        // micro = Pb + spread·qb/(qb+qa) = Pb + 0.25·0.75; mid = Pb + 0.125 → offset = +0.0625 = +0.25t
        assertThat(m.microPriceOffsetTicks()).isCloseTo(0.25, org.assertj.core.data.Offset.offset(EPS));
    }

    // ── Liquidity vacuum state machine ───────────────────────────────────────

    /** Feeds {@code count} healthy 100/100 snapshots at 500ms cadence starting at T0. */
    private Instant warmBaseline(int count) {
        Instant t = T0;
        analyzer.onSnapshot(book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 100, 100, t), t);
        for (int i = 1; i < count; i++) {
            t = T0.plusMillis(500L * i);
            analyzer.onSnapshot(book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 100, 100, t), t);
        }
        return t;
    }

    @Test
    void vacuum_requiresPersistence_beforeFlagging() {
        Instant t = warmBaseline(20); // T0 + 9.5s, baselines ≈ 100/100

        // bid side collapses to 30 (<40% of baseline) while asks hold at 100 (>=70%)
        DepthFlowMetrics m = null;
        for (long ms = 500; ms <= 2500; ms += 500) {
            m = feed(book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 30, 100, t.plusMillis(ms)),
                t.plusMillis(ms));
            assertThat(m.vacuumState())
                .as("pending persistence at +%dms", ms)
                .isEqualTo(DepthFlowMetrics.VacuumState.NORMAL);
        }
        assertThat(m.bidDepthRatio()).isLessThan(0.4);
        assertThat(m.askDepthRatio()).isGreaterThanOrEqualTo(0.7);

        // 3.0s after the depletion started → VACUUM_BID
        DepthFlowMetrics flagged = feed(
            book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 30, 100, t.plusMillis(3500)),
            t.plusMillis(3500));
        assertThat(flagged.vacuumState()).isEqualTo(DepthFlowMetrics.VacuumState.VACUUM_BID);
    }

    @Test
    void vacuum_askSide_flagsVacuumAsk() {
        Instant t = warmBaseline(20);
        DepthFlowMetrics m = null;
        for (long ms = 500; ms <= 3500; ms += 500) {
            m = feed(book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 100, 30, t.plusMillis(ms)),
                t.plusMillis(ms));
        }
        assertThat(m.vacuumState()).isEqualTo(DepthFlowMetrics.VacuumState.VACUUM_ASK);
    }

    @Test
    void thin_bothSidesBelowHalfBaseline_flagsImmediately() {
        Instant t = warmBaseline(20);
        DepthFlowMetrics m = feed(
            book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 40, 40, t.plusMillis(500)),
            t.plusMillis(500));
        assertThat(m.vacuumState()).isEqualTo(DepthFlowMetrics.VacuumState.THIN);
    }

    @Test
    void vacuum_interruptedDepletion_restartsThePersistenceClock() {
        // deeper warm-up: the depleted samples drag the rolling baseline down, and this
        // scenario feeds 13 of them — 40 healthy samples keep 30/baseline safely < 0.4
        Instant t = warmBaseline(40);
        // 2.5s of bid depletion — candidate pending
        for (long ms = 500; ms <= 2500; ms += 500) {
            feed(book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 30, 100, t.plusMillis(ms)),
                t.plusMillis(ms));
        }
        // bid recovers for one snapshot — candidate must reset
        feed(book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 100, 100, t.plusMillis(3000)),
            t.plusMillis(3000));
        // depletion resumes: 2.5s later it is still NOT a vacuum (clock restarted)
        DepthFlowMetrics m = null;
        for (long ms = 3500; ms <= 6000; ms += 500) {
            m = feed(book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 30, 100, t.plusMillis(ms)),
                t.plusMillis(ms));
        }
        assertThat(m.vacuumState()).isEqualTo(DepthFlowMetrics.VacuumState.NORMAL);
        // …and 3s after the resume it flags
        DepthFlowMetrics flagged = feed(
            book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 30, 100, t.plusMillis(6500)),
            t.plusMillis(6500));
        assertThat(flagged.vacuumState()).isEqualTo(DepthFlowMetrics.VacuumState.VACUUM_BID);
    }

    @Test
    void vacuum_baselineNotWarm_staysNormal() {
        analyzer.onSnapshot(book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 100, 100, T0), T0);
        // only a handful of samples — ratios must be neutral, state NORMAL
        DepthFlowMetrics m = feed(
            book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 10, 100, T0.plusMillis(500)),
            T0.plusMillis(500));
        assertThat(m.vacuumState()).isEqualTo(DepthFlowMetrics.VacuumState.NORMAL);
        assertThat(m.bidDepthRatio()).isEqualTo(1.0);
    }

    // ── Pull/stack net flow ──────────────────────────────────────────────────

    @Test
    void pullStack_aggregatesSizeChanges_beyondNoiseFloor() {
        List<DepthLevel> bids0 = List.of(lvl(21000.00, 10), lvl(20999.75, 8), lvl(20999.50, 6));
        List<DepthLevel> asks0 = List.of(lvl(21000.25, 5), lvl(21000.50, 7));
        // warm the baseline so pullStackScore is computable (>= 10 samples)
        Instant t = T0;
        analyzer.onSnapshot(book(bids0, asks0, 100, 100, t), t);
        for (int i = 1; i <= 10; i++) {
            t = T0.plusMillis(500L * i);
            analyzer.onSnapshot(book(bids0, asks0, 100, 100, t), t);
        }

        // bid best pulled 10→4 (-6); bid deep stacked 6→9 (+3); 20999.75 changes by +2 = noise;
        // ask 21000.50 stacked 7→12 (+5); 21000.25 unchanged
        List<DepthLevel> bids1 = List.of(lvl(21000.00, 4), lvl(20999.75, 10), lvl(20999.50, 9));
        List<DepthLevel> asks1 = List.of(lvl(21000.25, 5), lvl(21000.50, 12));
        DepthFlowMetrics m = feed(book(bids1, asks1, 100, 100, t.plusMillis(500)), t.plusMillis(500));

        assertThat(m.bidPulled10s()).isEqualTo(6);
        assertThat(m.bidStacked10s()).isEqualTo(3);
        assertThat(m.askPulled10s()).isEqualTo(0);
        assertThat(m.askStacked10s()).isEqualTo(5);
        // net = (3-6) - (5-0) = -8, normalized by the ~200 combined baseline → negative
        assertThat(m.pullStackScore()).isLessThan(0.0);
        assertThat(m.pullStackScore()).isCloseTo(-8.0 / 200.0, org.assertj.core.data.Offset.offset(0.005));
    }

    @Test
    void pullStack_levelsEnteringOrLeavingTheLadder_areIgnored() {
        Instant t = T0;
        List<DepthLevel> bids0 = List.of(lvl(21000.00, 10), lvl(20999.75, 8));
        List<DepthLevel> asks0 = List.of(lvl(21000.25, 5));
        analyzer.onSnapshot(book(bids0, asks0, 100, 100, t), t);

        // book shift: 20999.75 scrolls out, 20999.50 scrolls in — neither is pull/stack flow
        List<DepthLevel> bids1 = List.of(lvl(21000.00, 10), lvl(20999.50, 40));
        DepthFlowMetrics m = feed(book(bids1, asks0, 100, 100, t.plusMillis(500)), t.plusMillis(500));

        assertThat(m.bidPulled10s()).isEqualTo(0);
        assertThat(m.bidStacked10s()).isEqualTo(0);
    }

    @Test
    void pullStack_windowExpiresAfter10s() {
        Instant t = T0;
        List<DepthLevel> bids0 = List.of(lvl(21000.00, 10));
        List<DepthLevel> asks0 = List.of(lvl(21000.25, 5));
        analyzer.onSnapshot(book(bids0, asks0, 100, 100, t), t);
        DepthFlowMetrics first = feed(
            book(List.of(lvl(21000.00, 4)), asks0, 100, 100, t.plusMillis(500)), t.plusMillis(500));
        assertThat(first.bidPulled10s()).isEqualTo(6);

        // keep the book unchanged: the -6 pull must age out of the 10s window
        DepthFlowMetrics m = null;
        for (long ms = 1000; ms <= 11000; ms += 500) {
            m = feed(book(List.of(lvl(21000.00, 4)), asks0, 100, 100, t.plusMillis(ms)), t.plusMillis(ms));
        }
        assertThat(m.bidPulled10s()).isEqualTo(0);
    }

    // ── Stale-gap reset ──────────────────────────────────────────────────────

    @Test
    void evalGapBeyondThreshold_resetsAllState_andReprimes() {
        analyzer.onSnapshot(simpleBook(21000.00, 10, 21000.50, 10, T0), T0);
        DepthFlowMetrics before = feed(simpleBook(21000.25, 8, 21000.50, 10, T0), T0.plusMillis(500));
        assertThat(before.ofi10s()).isEqualTo(8.0);

        // 11.5s gap (> 10s stale gap) — the snapshot after it must PRIME, not emit
        assertThat(analyzer.onSnapshot(simpleBook(21000.25, 8, 21000.50, 10, T0), T0.plusSeconds(12)))
            .as("first snapshot after a feed gap re-primes")
            .isEmpty();

        // next transition emits with NO carried-over flow
        DepthFlowMetrics after = feed(
            simpleBook(21000.25, 8, 21000.50, 10, T0), T0.plusSeconds(12).plusMillis(500));
        assertThat(after.ofi10s()).isEqualTo(0.0);
        assertThat(after.bidPulled10s()).isEqualTo(0);
        assertThat(after.vacuumState()).isEqualTo(DepthFlowMetrics.VacuumState.NORMAL);
    }

    @Test
    void vacuumCandidate_doesNotSurviveAGap() {
        Instant t = warmBaseline(20);
        // 2.5s of bid depletion — candidate pending
        for (long ms = 500; ms <= 2500; ms += 500) {
            feed(book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 30, 100, t.plusMillis(ms)),
                t.plusMillis(ms));
        }
        // 11s gap, then depletion "continues": must NOT flag — baseline and candidate were reset
        Instant afterGap = t.plusMillis(2500).plusSeconds(11);
        assertThat(analyzer.onSnapshot(
            book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 30, 100, afterGap), afterGap))
            .isEmpty();
        DepthFlowMetrics m = feed(
            book(List.of(lvl(21000.00, 20)), List.of(lvl(21000.25, 20)), 30, 100, afterGap.plusMillis(500)),
            afterGap.plusMillis(500));
        assertThat(m.vacuumState()).isEqualTo(DepthFlowMetrics.VacuumState.NORMAL);
    }
}
