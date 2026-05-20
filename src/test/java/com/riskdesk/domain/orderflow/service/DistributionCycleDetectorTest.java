package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DistributionSignal;
import com.riskdesk.domain.orderflow.model.DistributionSignal.DistributionType;
import com.riskdesk.domain.orderflow.model.MomentumSignal;
import com.riskdesk.domain.orderflow.model.MomentumSignal.MomentumSide;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal.CyclePhase;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal.CycleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DistributionCycleDetectorTest {

    private final Instrument instrument = Instrument.MNQ;
    private DistributionCycleDetector detector;
    private Instant t0;

    @BeforeEach
    void setUp() {
        detector = new DistributionCycleDetector(
            instrument,
            Duration.ofMinutes(15),
            Duration.ofMinutes(30),
            Duration.ofMinutes(5));
        t0 = Instant.parse("2026-04-23T17:00:00Z");
    }

    private DistributionSignal distribution(double price, int confidence, Instant ts) {
        return new DistributionSignal(
            instrument, DistributionType.DISTRIBUTION,
            5, 5.0, 50.0, price, null, confidence, ts.minusSeconds(50), ts);
    }

    private DistributionSignal accumulation(double price, int confidence, Instant ts) {
        return new DistributionSignal(
            instrument, DistributionType.ACCUMULATION,
            5, 4.5, 45.0, price, null, confidence, ts.minusSeconds(45), ts);
    }

    private MomentumSignal bearishMomentum(double priceMove, double score, Instant ts) {
        return new MomentumSignal(instrument, MomentumSide.BEARISH_MOMENTUM,
            score, -1500, Math.abs(priceMove), priceMove, 18000, ts);
    }

    private MomentumSignal bullishMomentum(double priceMove, double score, Instant ts) {
        return new MomentumSignal(instrument, MomentumSide.BULLISH_MOMENTUM,
            score, 1500, priceMove, priceMove, 18000, ts);
    }

    @Test
    void distributionAlone_entersPhase1() {
        Optional<SmartMoneyCycleSignal> out = detector.onDistribution(
            distribution(27100.0, 80, t0), t0);
        assertThat(out).isPresent();
        assertThat(out.get().currentPhase()).isEqualTo(CyclePhase.PHASE_1);
        assertThat(out.get().cycleType()).isEqualTo(CycleType.BEARISH_CYCLE);
        assertThat(out.get().completedAt()).isNull();
    }

    @Test
    void momentumWithoutPhase1_returnsEmpty() {
        Optional<SmartMoneyCycleSignal> out = detector.onMomentum(
            bearishMomentum(-10.0, 4.0, t0), t0);
        assertThat(out).isEmpty();
    }

    @Test
    void fullBearishCycle_completesWithHighConfidence() {
        // Phase 1: distribution at premium
        detector.onDistribution(distribution(27100.0, 80, t0), t0);

        // Phase 2: bearish momentum 5 min later
        Instant t1 = t0.plus(Duration.ofMinutes(5));
        Optional<SmartMoneyCycleSignal> phase2 = detector.onMomentum(
            bearishMomentum(-50.0, 4.0, t1), t1);
        assertThat(phase2).isPresent();
        assertThat(phase2.get().currentPhase()).isEqualTo(CyclePhase.PHASE_2);

        // Phase 3: accumulation 10 min later (mirror)
        Instant t2 = t1.plus(Duration.ofMinutes(10));
        Optional<SmartMoneyCycleSignal> complete = detector.onDistribution(
            accumulation(26900.0, 75, t2), t2);

        assertThat(complete).isPresent();
        assertThat(complete.get().currentPhase()).isEqualTo(CyclePhase.COMPLETE);
        assertThat(complete.get().cycleType()).isEqualTo(CycleType.BEARISH_CYCLE);
        assertThat(complete.get().priceAtPhase1()).isEqualTo(27100.0);
        assertThat(complete.get().priceAtPhase3()).isEqualTo(26900.0);
        assertThat(complete.get().totalPriceMove()).isEqualTo(200.0);
        assertThat(complete.get().completedAt()).isNotNull();
        assertThat(complete.get().confidence()).isGreaterThanOrEqualTo(70);
    }

    @Test
    void fullBullishCycle_completes() {
        // Phase 1: accumulation at discount
        detector.onDistribution(accumulation(26900.0, 75, t0), t0);

        // Phase 2: bullish momentum
        Instant t1 = t0.plus(Duration.ofMinutes(3));
        detector.onMomentum(bullishMomentum(50.0, 4.0, t1), t1);

        // Phase 3: distribution (mirror)
        Instant t2 = t1.plus(Duration.ofMinutes(10));
        Optional<SmartMoneyCycleSignal> complete = detector.onDistribution(
            distribution(27150.0, 80, t2), t2);

        assertThat(complete).isPresent();
        assertThat(complete.get().currentPhase()).isEqualTo(CyclePhase.COMPLETE);
        assertThat(complete.get().cycleType()).isEqualTo(CycleType.BULLISH_CYCLE);
    }

    @Test
    void wrongDirectionMomentum_doesNotAdvance() {
        detector.onDistribution(distribution(27100.0, 80, t0), t0);

        Instant t1 = t0.plus(Duration.ofMinutes(5));
        Optional<SmartMoneyCycleSignal> out = detector.onMomentum(
            bullishMomentum(+50.0, 4.0, t1), t1);
        // Still in phase 1 — no advancement output
        assertThat(out).isEmpty();
    }

    @Test
    void mirrorArrivesAfterWindow_doesNotComplete() {
        detector.onDistribution(distribution(27100.0, 80, t0), t0);
        Instant t1 = t0.plus(Duration.ofMinutes(5));
        detector.onMomentum(bearishMomentum(-50.0, 4.0, t1), t1);

        // Mirror arrives 45 min after phase 2 → exceeds 30 min window
        Instant t2 = t1.plus(Duration.ofMinutes(45));
        detector.tick(t2);
        // State machine should have reset during the tick
        Optional<SmartMoneyCycleSignal> out = detector.onDistribution(
            accumulation(26900.0, 75, t2), t2);
        // After timeout reset, new accumulation enters fresh PHASE_1 (BULLISH_CYCLE) — not COMPLETE
        assertThat(out).isPresent();
        assertThat(out.get().currentPhase()).isEqualTo(CyclePhase.PHASE_1);
        assertThat(out.get().cycleType()).isEqualTo(CycleType.BULLISH_CYCLE);
    }

    @Test
    void phase1ExpiresWithoutMomentum() {
        detector.onDistribution(distribution(27100.0, 80, t0), t0);

        // 20 minutes later, tick should expire phase 1 (momentumWindow = 15 min)
        Instant far = t0.plus(Duration.ofMinutes(20));
        detector.tick(far);

        // New distribution starts fresh phase 1, does not complete anything
        Optional<SmartMoneyCycleSignal> out = detector.onDistribution(
            distribution(27200.0, 80, far), far);
        assertThat(out).isPresent();
        assertThat(out.get().currentPhase()).isEqualTo(CyclePhase.PHASE_1);
    }

    @Test
    void cooldownAfterComplete_blocksImmediateRefire() {
        // Complete a cycle
        detector.onDistribution(distribution(27100.0, 80, t0), t0);
        Instant t1 = t0.plus(Duration.ofMinutes(5));
        detector.onMomentum(bearishMomentum(-50.0, 4.0, t1), t1);
        Instant t2 = t1.plus(Duration.ofMinutes(10));
        detector.onDistribution(accumulation(26900.0, 75, t2), t2);

        // Immediately try to start a new cycle — cooldown active
        Optional<SmartMoneyCycleSignal> blocked = detector.onDistribution(
            distribution(27000.0, 70, t2.plusSeconds(10)), t2.plusSeconds(10));
        assertThat(blocked).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Direction-flip regression tests (Bug: PHASE_1 opposite-direction was
    // silently swallowing the flip and never publishing the new cycle direction)
    // -----------------------------------------------------------------------

    @Test
    void oppositeDistributionInBullPhase1_emitsBearPhase1() {
        // Start a BULLISH_CYCLE via accumulation
        Optional<SmartMoneyCycleSignal> bull = detector.onDistribution(
            accumulation(26900.0, 75, t0), t0);
        assertThat(bull).isPresent();
        assertThat(bull.get().cycleType()).isEqualTo(CycleType.BULLISH_CYCLE);
        assertThat(bull.get().currentPhase()).isEqualTo(CyclePhase.PHASE_1);

        // Distribution arrives while still in PHASE_1 — market flipped bearish
        Instant t1 = t0.plusSeconds(90);
        Optional<SmartMoneyCycleSignal> bear = detector.onDistribution(
            distribution(27050.0, 78, t1), t1);

        // Must emit a new PHASE_1 signal with BEARISH direction — not swallow it
        assertThat(bear).isPresent();
        assertThat(bear.get().cycleType()).isEqualTo(CycleType.BEARISH_CYCLE);
        assertThat(bear.get().currentPhase()).isEqualTo(CyclePhase.PHASE_1);
        assertThat(bear.get().completedAt()).isNull();
    }

    @Test
    void oppositeAccumulationInBearPhase1_emitsBullPhase1() {
        // Start a BEARISH_CYCLE via distribution
        Optional<SmartMoneyCycleSignal> bear = detector.onDistribution(
            distribution(27100.0, 80, t0), t0);
        assertThat(bear).isPresent();
        assertThat(bear.get().cycleType()).isEqualTo(CycleType.BEARISH_CYCLE);

        // Accumulation arrives — market absorbed the sell pressure, flipping bullish
        Instant t1 = t0.plusSeconds(60);
        Optional<SmartMoneyCycleSignal> bull = detector.onDistribution(
            accumulation(26950.0, 72, t1), t1);

        assertThat(bull).isPresent();
        assertThat(bull.get().cycleType()).isEqualTo(CycleType.BULLISH_CYCLE);
        assertThat(bull.get().currentPhase()).isEqualTo(CyclePhase.PHASE_1);
        assertThat(bull.get().priceAtPhase1()).isEqualTo(26950.0);
    }

    @Test
    void flipInPhase1_newCycleCanCompleteInOppositeDirection() {
        // Start BULLISH_CYCLE
        detector.onDistribution(accumulation(26900.0, 75, t0), t0);

        // Flip to BEARISH_CYCLE P1
        Instant t1 = t0.plusSeconds(90);
        detector.onDistribution(distribution(27050.0, 78, t1), t1);

        // Bearish momentum advances P1 → P2
        Instant t2 = t1.plus(Duration.ofMinutes(3));
        Optional<SmartMoneyCycleSignal> phase2 = detector.onMomentum(
            bearishMomentum(-60.0, 4.5, t2), t2);
        assertThat(phase2).isPresent();
        assertThat(phase2.get().cycleType()).isEqualTo(CycleType.BEARISH_CYCLE);
        assertThat(phase2.get().currentPhase()).isEqualTo(CyclePhase.PHASE_2);

        // Mirror accumulation completes the cycle
        Instant t3 = t2.plus(Duration.ofMinutes(8));
        Optional<SmartMoneyCycleSignal> complete = detector.onDistribution(
            accumulation(26800.0, 70, t3), t3);
        assertThat(complete).isPresent();
        assertThat(complete.get().currentPhase()).isEqualTo(CyclePhase.COMPLETE);
        assertThat(complete.get().cycleType()).isEqualTo(CycleType.BEARISH_CYCLE);
    }

    @Test
    void sameDirectionInPhase1_doesNotEmitSignal() {
        // Same-direction refresh should stay silent (debounce)
        detector.onDistribution(distribution(27100.0, 80, t0), t0);
        Instant t1 = t0.plusSeconds(30);
        Optional<SmartMoneyCycleSignal> out = detector.onDistribution(
            distribution(27110.0, 82, t1), t1);
        assertThat(out).isEmpty();
    }

    @Test
    void sameSideDistributionInPhase2_emitsNewPhase1() {
        // Build a BEARISH_CYCLE up to PHASE_2
        detector.onDistribution(distribution(27100.0, 80, t0), t0);
        Instant t1 = t0.plus(Duration.ofMinutes(5));
        Optional<SmartMoneyCycleSignal> p2 = detector.onMomentum(
            bearishMomentum(-50.0, 4.0, t1), t1);
        assertThat(p2).isPresent();
        assertThat(p2.get().currentPhase()).isEqualTo(CyclePhase.PHASE_2);

        // Another DISTRIBUTION arrives in PHASE_2 (same side, not the expected ACCUMULATION mirror)
        Instant t2 = t1.plus(Duration.ofMinutes(3));
        Optional<SmartMoneyCycleSignal> reset = detector.onDistribution(
            distribution(27050.0, 78, t2), t2);

        // Cycle invalidated, fresh BEARISH_CYCLE PHASE_1 emitted (was previously silent)
        assertThat(reset).isPresent();
        assertThat(reset.get().currentPhase()).isEqualTo(CyclePhase.PHASE_1);
        assertThat(reset.get().cycleType()).isEqualTo(CycleType.BEARISH_CYCLE);
        assertThat(reset.get().completedAt()).isNull();
    }

    @Test
    void confidenceStaysBounded() {
        detector.onDistribution(distribution(27100.0, 80, t0), t0);
        Instant t1 = t0.plus(Duration.ofMinutes(5));
        detector.onMomentum(bearishMomentum(-50.0, 20.0, t1), t1); // extreme score
        Instant t2 = t1.plus(Duration.ofMinutes(10));
        Optional<SmartMoneyCycleSignal> out = detector.onDistribution(
            accumulation(26900.0, 100, t2), t2);

        assertThat(out).isPresent();
        assertThat(out.get().confidence()).isBetween(0, 100);
    }
}
