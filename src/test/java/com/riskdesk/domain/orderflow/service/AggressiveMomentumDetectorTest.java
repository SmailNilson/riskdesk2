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
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        detector = new AggressiveMomentumDetector(); // threshold 2.0, fraction 0.3
    }

    @Test
    void deltaAndPriceOpposite_returnsEmpty() {
        // Delta negative (sellers) but price moves up → not momentum (absorption territory)
        Optional<MomentumSignal> out = detector.evaluate(
            instrument, -500, +3.0, 3.0, 1000, 10.0, 100.0, 400.0, now);
        assertThat(out).isEmpty();
    }

    @Test
    void priceMoveTooSmall_returnsEmpty() {
        // Price move 0.5 ticks vs ATR 10 → 5% << 30% required
        Optional<MomentumSignal> out = detector.evaluate(
            instrument, -500, -0.5, 0.5, 1000, 10.0, 100.0, 400.0, now);
        assertThat(out).isEmpty();
    }

    @Test
    void alignedDeltaAndPriceWithVolume_firesBearishMomentum() {
        // delta -500, price -5 ticks, volume 1000, atr 10, threshold 100, avg 400
        // score = 5 * 0.5 * 2.5 = 6.25 > 2.0
        Optional<MomentumSignal> out = detector.evaluate(
            instrument, -500, -5.0, 5.0, 1000, 10.0, 100.0, 400.0, now);
        assertThat(out).isPresent();
        assertThat(out.get().side()).isEqualTo(MomentumSide.BEARISH_MOMENTUM);
        assertThat(out.get().momentumScore()).isGreaterThan(2.0);
    }

    @Test
    void alignedBullishFires() {
        Optional<MomentumSignal> out = detector.evaluate(
            instrument, 500, 5.0, 5.0, 1000, 10.0, 100.0, 400.0, now);
        assertThat(out).isPresent();
        assertThat(out.get().side()).isEqualTo(MomentumSide.BULLISH_MOMENTUM);
    }

    @Test
    void zeroDelta_returnsEmpty() {
        Optional<MomentumSignal> out = detector.evaluate(
            instrument, 0, 2.0, 2.0, 1000, 10.0, 100.0, 400.0, now);
        assertThat(out).isEmpty();
    }

    @Test
    void zeroAtr_returnsEmptyGracefully() {
        Optional<MomentumSignal> out = detector.evaluate(
            instrument, -500, -5.0, 5.0, 1000, 0.0, 100.0, 400.0, now);
        assertThat(out).isEmpty();
    }

    @Test
    void scoreBelowThreshold_returnsEmpty() {
        // Fraction of ATR just above 30% but score too low
        // score = (50/100) * (3.5/10) * (100/400) = 0.044 < 2.0
        Optional<MomentumSignal> out = detector.evaluate(
            instrument, -50, -3.5, 3.5, 100, 10.0, 100.0, 400.0, now);
        assertThat(out).isEmpty();
    }

    @Test
    void signalCarriesAllFields() {
        Optional<MomentumSignal> out = detector.evaluate(
            instrument, -1800, -15.0, 15.0, 18000, 10.0, 100.0, 4000.0, now);
        assertThat(out).isPresent();
        MomentumSignal s = out.get();
        assertThat(s.instrument()).isEqualTo(instrument);
        assertThat(s.aggressiveDelta()).isEqualTo(-1800);
        assertThat(s.priceMoveTicks()).isEqualTo(15.0);
        assertThat(s.priceMovePoints()).isEqualTo(-15.0);
        assertThat(s.totalVolume()).isEqualTo(18000);
        assertThat(s.timestamp()).isEqualTo(now);
    }
}
