package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal.AbsorptionSide;
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

    @Test
    void scoreBelowThreshold_returnsEmpty() {
        // Small delta, small volume => low score
        // score = (50/100) * (1 - 1/10) * (100/200) = 0.5 * 0.9 * 0.5 = 0.225 < 2.0
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -50, 1.0, 100, 10.0, 100.0, 200.0, now);

        assertThat(result).isEmpty();
    }

    @Test
    void scoreAboveThreshold_returnsSignal() {
        // Large delta, stable price, high volume
        // score = (500/100) * (1 - 0.5/10) * (600/200) = 5.0 * 0.95 * 3.0 = 14.25 > 2.0
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 0.5, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isPresent();
        AbsorptionSignal signal = result.get();
        assertThat(signal.absorptionScore()).isGreaterThan(2.0);
        assertThat(signal.instrument()).isEqualTo(instrument);
        assertThat(signal.totalVolume()).isEqualTo(600);
        assertThat(signal.aggressiveDelta()).isEqualTo(-500);
    }

    @Test
    void negativeDelta_stablePrice_returnsBullishAbsorption() {
        // Negative delta = sellers aggressive, but price stable = buyers absorbing = BULLISH
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 0.5, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isPresent();
        assertThat(result.get().side()).isEqualTo(AbsorptionSide.BULLISH_ABSORPTION);
    }

    @Test
    void positiveDelta_stablePrice_returnsBearishAbsorption() {
        // Positive delta = buyers aggressive, but price stable = sellers absorbing = BEARISH
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, 500, 0.5, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isPresent();
        assertThat(result.get().side()).isEqualTo(AbsorptionSide.BEARISH_ABSORPTION);
    }

    @Test
    void zeroVolume_avgVolume_returnsEmpty_noDivisionError() {
        // avgVolume = 0 => guard clause returns empty
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 0.5, 600, 10.0, 100.0, 0.0, now);

        assertThat(result).isEmpty();
    }

    @Test
    void zeroDeltaThreshold_returnsEmpty() {
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 0.5, 600, 10.0, 0.0, 200.0, now);

        assertThat(result).isEmpty();
    }

    @Test
    void zeroAtr_returnsEmpty() {
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 0.5, 600, 0.0, 100.0, 200.0, now);

        assertThat(result).isEmpty();
    }

    @Test
    void largePriceMove_lowScore_returnsEmpty() {
        // priceMoveTicks = 15.0, atr = 10.0 => priceStability = 1 - 15/10 = -0.5 => <= 0 => empty
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 15.0, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isEmpty();
    }

    @Test
    void priceMove_equalToAtr_returnsEmpty() {
        // priceStability = 1 - 10/10 = 0 => <= 0 => empty
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 10.0, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isEmpty();
    }

    @Test
    void scoreExactlyAtThreshold_returnsEmpty() {
        // We need score = exactly 2.0 => <= 2.0 => empty (strict >)
        // score = (delta/deltaT) * (1 - priceMove/atr) * (vol/avgVol)
        // Let's compute: (200/100) * (1 - 0/10) * (200/200) = 2.0 * 1.0 * 1.0 = 2.0
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -200, 0.0, 200, 10.0, 100.0, 200.0, now);

        assertThat(result).isEmpty();
    }

    @Test
    void scoreJustAboveThreshold_returnsSignal() {
        // (201/100) * (1 - 0/10) * (200/200) = 2.01 > 2.0
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -201, 0.0, 200, 10.0, 100.0, 200.0, now);

        assertThat(result).isPresent();
    }

    @Test
    void signalContainsCorrectTimestamp() {
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 0.5, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isPresent();
        assertThat(result.get().timestamp()).isEqualTo(now);
    }

    @Test
    void signalContainsCorrectPriceMoveTicks() {
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 2.5, 600, 10.0, 100.0, 200.0, now);

        assertThat(result).isPresent();
        assertThat(result.get().priceMoveTicks()).isEqualTo(2.5);
    }

    @Test
    void zeroVolumePassed_returnsEmpty_becauseAvgVolumeGuard() {
        // volume=0 but avgVolume=200 => volumeComponent = 0 => score = 0 => empty
        Optional<AbsorptionSignal> result = detector.evaluate(
                instrument, -500, 0.5, 0, 10.0, 100.0, 200.0, now);

        assertThat(result).isEmpty();
    }
}
