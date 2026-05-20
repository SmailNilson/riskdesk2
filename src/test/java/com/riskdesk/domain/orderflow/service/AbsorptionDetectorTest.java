package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal.AbsorptionSide;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal.AbsorptionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AbsorptionDetectorTest {

    private AbsorptionDetector detector;
    private final Instrument instrument = Instrument.MCL;
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        detector = new AbsorptionDetector();
    }

    // ─── 4-quadrant decision rule ─────────────────────────────────────────

    @Test
    void negativeDelta_priceUp_returnsBullishDivergence() {
        // delta < 0 + price ↑ → buyers absorbing sell pressure → BULL DIVERGENCE
        // DIVERGENCE score = (500/100) * (5/10) * (600/200) = 5 * 0.5 * 3 = 7.5 > 1.5
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 5.0, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isPresent();
        AbsorptionSignal s = result.get();
        assertThat(s.side()).isEqualTo(AbsorptionSide.BULLISH_ABSORPTION);
        assertThat(s.absorptionType()).isEqualTo(AbsorptionType.DIVERGENCE);
        assertThat(s.explanation()).isEqualTo("Buyers absorbing sell pressure");
    }

    @Test
    void positiveDelta_priceDown_returnsBearishDivergence() {
        // delta > 0 + price ↓ → sellers absorbing buy pressure → BEAR DIVERGENCE
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, 500, -5.0, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isPresent();
        AbsorptionSignal s = result.get();
        assertThat(s.side()).isEqualTo(AbsorptionSide.BEARISH_ABSORPTION);
        assertThat(s.absorptionType()).isEqualTo(AbsorptionType.DIVERGENCE);
        assertThat(s.explanation()).isEqualTo("Sellers absorbing buy pressure");
    }

    @Test
    void negativeDelta_priceDown_returnsBearishClassic() {
        // delta < 0 + price ↓ → trend confirmation → BEAR CLASSIC
        // CLASSIC score = (500/100) * (600/200) = 5 * 3 = 15 > 2.0
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, -5.0, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isPresent();
        AbsorptionSignal s = result.get();
        assertThat(s.side()).isEqualTo(AbsorptionSide.BEARISH_ABSORPTION);
        assertThat(s.absorptionType()).isEqualTo(AbsorptionType.CLASSIC);
        assertThat(s.explanation()).isEqualTo("Classic bear confirmation");
    }

    @Test
    void positiveDelta_priceUp_returnsBullishClassic() {
        // delta > 0 + price ↑ → trend confirmation → BULL CLASSIC
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, 500, 5.0, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isPresent();
        AbsorptionSignal s = result.get();
        assertThat(s.side()).isEqualTo(AbsorptionSide.BULLISH_ABSORPTION);
        assertThat(s.absorptionType()).isEqualTo(AbsorptionType.CLASSIC);
        assertThat(s.explanation()).isEqualTo("Classic bull confirmation");
    }

    // ─── NEUTRAL guards (delta or price is zero) ───────────────────────────

    @Test
    void zeroDelta_returnsEmpty_neutral() {
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, 0, 5.0, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isEmpty();
    }

    @Test
    void zeroSignedPriceMove_returnsEmpty_neutral() {
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 0.0, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isEmpty();
    }

    @Test
    void nanSignedPriceMove_returnsEmpty() {
        // Orchestrator passes 0.0 when first/last prices are NaN, but defend at the detector boundary too.
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, Double.NaN, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isEmpty();
    }

    // ─── Normalizer guards ────────────────────────────────────────────────

    @Test
    void zeroAvgVolume_returnsEmpty() {
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 5.0, 600, 10.0, 100.0, 0.0, now);

        assertThat(result).isEmpty();
    }

    @Test
    void zeroDeltaThreshold_returnsEmpty() {
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 5.0, 600, 10.0, 0.0, 200.0, now);

        assertThat(result).isEmpty();
    }

    @Test
    void zeroAtr_returnsEmpty() {
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 5.0, 600, 0.0, 100.0, 200.0, now);

        assertThat(result).isEmpty();
    }

    // ─── Score gates ──────────────────────────────────────────────────────

    @Test
    void classic_scoreExactlyAtThreshold_returnsEmpty() {
        // CLASSIC score = (200/100) * (200/200) = 2.0 — strict > gate, so empty.
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, 200, 1.0, 200, 10.0, 100.0, 200.0, now);

        assertThat(result).isEmpty();
    }

    @Test
    void classic_scoreJustAboveThreshold_returnsSignal() {
        // CLASSIC score = (201/100) * (200/200) = 2.01 > 2.0 → fires.
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, 201, 1.0, 200, 10.0, 100.0, 200.0, now);

        assertThat(result).isPresent();
        assertThat(result.get().absorptionType()).isEqualTo(AbsorptionType.CLASSIC);
    }

    @Test
    void divergence_scoreExactlyAtThreshold_returnsEmpty() {
        // DIVERGENCE score = (150/100) * (10/10) * (200/200) = 1.5 — strict > gate, so empty.
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -150, 10.0, 200, 10.0, 100.0, 200.0, now);

        assertThat(result).isEmpty();
    }

    @Test
    void divergence_scoreJustAboveThreshold_returnsSignal() {
        // DIVERGENCE score = (151/100) * (10/10) * (200/200) = 1.51 > 1.5 → fires.
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -151, 10.0, 200, 10.0, 100.0, 200.0, now);

        assertThat(result).isPresent();
        assertThat(result.get().absorptionType()).isEqualTo(AbsorptionType.DIVERGENCE);
    }

    // ─── Asymmetric thresholds (DIVERGENCE 1.5 vs CLASSIC 2.0) ────────────

    @Test
    void divergence_firesAtScore_1_8_butSameMagnitudesClassicDoesNotFire() {
        // DIVERGENCE quadrant (delta < 0, price ↑) — score 1.8 > 1.5 → fires.
        // delta=180, threshold=100, vol=200, avg=200, |move|=10, atr=10
        // DIVERGENCE = 1.8 * 1.0 * 1.0 = 1.8 > 1.5
        Optional<AbsorptionSignal> divergence = detector.evaluate(
                instrument, -180, 10.0, 200, 10.0, 100.0, 200.0, now);
        assertThat(divergence).isPresent();
        assertThat(divergence.get().absorptionType()).isEqualTo(AbsorptionType.DIVERGENCE);
        assertThat(divergence.get().absorptionScore()).isEqualTo(1.8);

        // CLASSIC quadrant (delta < 0, price ↓) — same |delta|, |vol|, |move|, |atr|
        // CLASSIC = 1.8 * 1.0 = 1.8 ≤ 2.0 → empty.
        Optional<AbsorptionSignal> classic = detector.evaluate(
                instrument, -180, -10.0, 200, 10.0, 100.0, 200.0, now);
        assertThat(classic).isEmpty();
    }

    // ─── Divergence amplifier (priceMove/atr boosts the score) ────────────

    @Test
    void divergenceLargeMove_scoresHigherThanSmallMoveSameDeltaVolume() {
        // Same delta and volume; bigger counter-move ⇒ higher score.
        // Memory entry MNQ 28-Apr scenario: delta -50…-100 + price holding/up = strong absorption.
        Optional<AbsorptionSignal> small = detector.evaluate(
                instrument, -500, 2.0, 600, 10.0, 100.0, 200.0, now);
        Optional<AbsorptionSignal> large = detector.evaluate(
                instrument, -500, 8.0, 600, 10.0, 100.0, 200.0, now);

        assertThat(small).isPresent();
        assertThat(large).isPresent();
        assertThat(large.get().absorptionScore()).isGreaterThan(small.get().absorptionScore());
        assertThat(small.get().absorptionType()).isEqualTo(AbsorptionType.DIVERGENCE);
        assertThat(large.get().absorptionType()).isEqualTo(AbsorptionType.DIVERGENCE);
    }

    // ─── Signal payload integrity ─────────────────────────────────────────

    @Test
    void signalCarriesTimestamp_delta_volume_priceMoveAbsolute() {
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 2.5, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isPresent();
        AbsorptionSignal s = result.get();
        assertThat(s.timestamp()).isEqualTo(now);
        assertThat(s.aggressiveDelta()).isEqualTo(-500);
        assertThat(s.totalVolume()).isEqualTo(600);
        // priceMoveTicks on the signal is the ABSOLUTE magnitude (back-compat with consumers).
        assertThat(s.priceMoveTicks()).isEqualTo(2.5);
    }

    @Test
    void signalPriceMoveTicks_isAbsoluteEvenForNegativeSignedMove() {
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, 500, -2.5, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isPresent();
        assertThat(result.get().priceMoveTicks()).isEqualTo(2.5);
    }
}
