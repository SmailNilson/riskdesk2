package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.MomentumSignal;
import com.riskdesk.domain.orderflow.model.MomentumSignal.MomentumSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AggressiveMomentumDetectorTest {

    private AggressiveMomentumDetector detector;
    private final Instrument instrument = Instrument.MNQ;
    private final Instant now = Instant.parse("2026-04-23T14:00:00Z");

    @BeforeEach
    void setUp() {
        // default constructor: threshold 0.55, fraction 0.4
        detector = new AggressiveMomentumDetector();
    }

    // Helper — all existing calls receive currentPrice=0.0 (no previous fire → debounce inactive)
    private Optional<MomentumSignal> eval(long delta, double pricePoints, double priceTicks,
                                           long volume, double atr, double deltaThreshold,
                                           double avgVolume) {
        return detector.evaluate(instrument, delta, pricePoints, priceTicks, volume,
                                 atr, deltaThreshold, avgVolume, 0.0, now);
    }

    @Test
    void deltaAndPriceOpposite_returnsEmpty() {
        // Delta negative (sellers) but price moves up → not momentum (absorption territory)
        Optional<MomentumSignal> out = eval(-500, +3.0, 3.0, 1000, 10.0, 100.0, 400.0);
        assertThat(out).isEmpty();
    }

    @Test
    void priceMoveTooSmall_returnsEmpty() {
        // Price move 0.5 ticks vs ATR 10 → 5% << 40% required
        Optional<MomentumSignal> out = eval(-500, -0.5, 0.5, 1000, 10.0, 100.0, 400.0);
        assertThat(out).isEmpty();
    }

    @Test
    void alignedDeltaAndPriceWithVolume_firesBearishMomentum() {
        // delta -500, price -5 ticks, volume 1000, atr 10, deltaThreshold 100, avg 400
        // scoreDelta  = sigmoid(5, 2)   ≈ 1.00
        // scorePrice  = sigmoid(0.5, 3) ≈ 0.18
        // scoreVolume = sigmoid(2.5, 2) ≈ 0.95
        // score = 0.40*1.00 + 0.35*0.18 + 0.25*0.95 ≈ 0.70 > 0.55
        Optional<MomentumSignal> out = eval(-500, -5.0, 5.0, 1000, 10.0, 100.0, 400.0);
        assertThat(out).isPresent();
        assertThat(out.get().side()).isEqualTo(MomentumSide.BEARISH_MOMENTUM);
        assertThat(out.get().momentumScore()).isGreaterThan(0.55);
        assertThat(out.get().momentumScore()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void alignedBullishFires() {
        Optional<MomentumSignal> out = eval(500, 5.0, 5.0, 1000, 10.0, 100.0, 400.0);
        assertThat(out).isPresent();
        assertThat(out.get().side()).isEqualTo(MomentumSide.BULLISH_MOMENTUM);
    }

    @Test
    void zeroDelta_returnsEmpty() {
        Optional<MomentumSignal> out = eval(0, 2.0, 2.0, 1000, 10.0, 100.0, 400.0);
        assertThat(out).isEmpty();
    }

    @Test
    void zeroAtr_returnsEmptyGracefully() {
        Optional<MomentumSignal> out = eval(-500, -5.0, 5.0, 1000, 0.0, 100.0, 400.0);
        assertThat(out).isEmpty();
    }

    @Test
    void scoreBelowThreshold_returnsEmpty() {
        // priceFraction = 3.5/10 = 0.35 < 0.4 minPriceMoveFraction → filtered before scoring
        Optional<MomentumSignal> out = eval(-50, -3.5, 3.5, 100, 10.0, 100.0, 400.0);
        assertThat(out).isEmpty();
    }

    @Test
    void signalCarriesAllFields() {
        Optional<MomentumSignal> out = detector.evaluate(
            instrument, -1800, -15.0, 15.0, 18000, 10.0, 100.0, 4000.0, 27000.0, now);
        assertThat(out).isPresent();
        MomentumSignal s = out.get();
        assertThat(s.instrument()).isEqualTo(instrument);
        assertThat(s.aggressiveDelta()).isEqualTo(-1800);
        assertThat(s.priceMoveTicks()).isEqualTo(15.0);
        assertThat(s.priceMovePoints()).isEqualTo(-15.0);
        assertThat(s.totalVolume()).isEqualTo(18000);
        assertThat(s.timestamp()).isEqualTo(now);
    }

    // ---- Debounce tests -------------------------------------------------------

    @Test
    void debounce_sameDirectionWithinAtrDistance_suppressed() {
        // ATR=20, ATR_DISTANCE=0.5 → need 10 pts movement before re-fire
        // First fire at price 27100
        Optional<MomentumSignal> first = detector.evaluate(
            instrument, -500, -10.0, 10.0, 1000, 20.0, 100.0, 400.0, 27100.0, now);
        assertThat(first).isPresent();

        // Second attempt at 27095 — only 5 pts away (< 0.5 × 20 = 10) → suppressed
        Instant t1 = now.plusSeconds(5);
        Optional<MomentumSignal> second = detector.evaluate(
            instrument, -500, -10.0, 10.0, 1000, 20.0, 100.0, 400.0, 27095.0, t1);
        assertThat(second).isEmpty();
    }

    @Test
    void debounce_sameDirectionBeyondAtrDistance_fires() {
        // ATR=20, ATR_DISTANCE=0.5 → need 10 pts movement
        Optional<MomentumSignal> first = detector.evaluate(
            instrument, -500, -10.0, 10.0, 1000, 20.0, 100.0, 400.0, 27100.0, now);
        assertThat(first).isPresent();

        // 11 pts away from last fire → exceeds 0.5 ATR → fires
        Instant t1 = now.plusSeconds(5);
        Optional<MomentumSignal> second = detector.evaluate(
            instrument, -500, -10.0, 10.0, 1000, 20.0, 100.0, 400.0, 27089.0, t1);
        assertThat(second).isPresent();
    }

    @Test
    void debounce_oppositeDirectionIgnoresAtrDistance_fires() {
        // Bear fire at 27100 — bull momentum at same price should not be blocked by bear debounce
        Optional<MomentumSignal> bearFire = detector.evaluate(
            instrument, -500, -10.0, 10.0, 1000, 20.0, 100.0, 400.0, 27100.0, now);
        assertThat(bearFire).isPresent();

        // Bullish momentum at 27100 — different side, no distance required
        Instant t1 = now.plusSeconds(5);
        Optional<MomentumSignal> bullFire = detector.evaluate(
            instrument, 500, 10.0, 10.0, 1000, 20.0, 100.0, 400.0, 27100.0, t1);
        assertThat(bullFire).isPresent();
        assertThat(bullFire.get().side()).isEqualTo(MomentumSide.BULLISH_MOMENTUM);
    }

    @Test
    void rateCap_maxFiresPerMinute_suppressesExcess() {
        // Default MAX_FIRES_PER_MINUTE = 2
        // Fire 1 (bear, big distance)
        Optional<MomentumSignal> f1 = detector.evaluate(
            instrument, -500, -10.0, 10.0, 1000, 5.0, 100.0, 400.0, 27100.0, now);
        assertThat(f1).isPresent();

        // Fire 2 (bull, far away — different side, no distance limit)
        Instant t1 = now.plusSeconds(5);
        Optional<MomentumSignal> f2 = detector.evaluate(
            instrument, 500, 10.0, 10.0, 1000, 5.0, 100.0, 400.0, 27080.0, t1);
        assertThat(f2).isPresent();

        // Fire 3 — rate cap reached (2 fires in last 60s), bear far enough but capped
        Instant t2 = now.plusSeconds(10);
        Optional<MomentumSignal> f3 = detector.evaluate(
            instrument, -500, -10.0, 10.0, 1000, 5.0, 100.0, 400.0, 27050.0, t2);
        assertThat(f3).isEmpty();
    }

    @Test
    void rateCap_resetsAfterOneMinute_allowsFire() {
        // Fill the rate cap
        detector.evaluate(instrument, -500, -10.0, 10.0, 1000, 5.0, 100.0, 400.0, 27100.0, now);
        Instant t1 = now.plusSeconds(5);
        detector.evaluate(instrument, 500, 10.0, 10.0, 1000, 5.0, 100.0, 400.0, 27080.0, t1);

        // 65 seconds later — rate cap window expired
        Instant t2 = now.plusSeconds(65);
        Optional<MomentumSignal> late = detector.evaluate(
            instrument, -500, -10.0, 10.0, 1000, 5.0, 100.0, 400.0, 27000.0, t2);
        assertThat(late).isPresent();
    }

    @Test
    void customConstructor_atrDistance_overridesDefault() {
        // Custom detector with much larger ATR distance (2.0) — should suppress closer re-fires
        AggressiveMomentumDetector strict = new AggressiveMomentumDetector(0.55, 0.4, 2.0, 2);
        Optional<MomentumSignal> first = strict.evaluate(
            instrument, -500, -10.0, 10.0, 1000, 20.0, 100.0, 400.0, 27100.0, now);
        assertThat(first).isPresent();

        // 25 pts away = 1.25 ATR — would fire under default 0.5, but suppressed under 2.0
        Instant t1 = now.plusSeconds(5);
        Optional<MomentumSignal> second = strict.evaluate(
            instrument, -500, -10.0, 10.0, 1000, 20.0, 100.0, 400.0, 27075.0, t1);
        assertThat(second).isEmpty();
    }

    @Test
    void customConstructor_maxFiresPerMinute_overridesDefault() {
        // Allow 5 fires/min instead of default 2
        AggressiveMomentumDetector permissive = new AggressiveMomentumDetector(0.55, 0.4, 0.5, 5);
        // Fire 4 times alternating side at large distance — all should pass
        Optional<MomentumSignal> f1 = permissive.evaluate(
            instrument, -500, -10.0, 10.0, 1000, 5.0, 100.0, 400.0, 27100.0, now);
        Optional<MomentumSignal> f2 = permissive.evaluate(
            instrument, 500, 10.0, 10.0, 1000, 5.0, 100.0, 400.0, 27200.0, now.plusSeconds(5));
        Optional<MomentumSignal> f3 = permissive.evaluate(
            instrument, -500, -10.0, 10.0, 1000, 5.0, 100.0, 400.0, 27000.0, now.plusSeconds(10));
        Optional<MomentumSignal> f4 = permissive.evaluate(
            instrument, 500, 10.0, 10.0, 1000, 5.0, 100.0, 400.0, 27300.0, now.plusSeconds(15));
        assertThat(f1).isPresent();
        assertThat(f2).isPresent();
        assertThat(f3).isPresent();  // Would be blocked by default cap=2, allowed by custom cap=5
        assertThat(f4).isPresent();
    }

    @Test
    void reset_clearsDebounceState() {
        // Fire, then reset, then fire again at the same price — should succeed after reset
        detector.evaluate(instrument, -500, -10.0, 10.0, 1000, 20.0, 100.0, 400.0, 27100.0, now);
        detector.reset();

        Instant t1 = now.plusSeconds(5);
        Optional<MomentumSignal> afterReset = detector.evaluate(
            instrument, -500, -10.0, 10.0, 1000, 20.0, 100.0, 400.0, 27100.0, t1);
        assertThat(afterReset).isPresent();
    }
}
