package com.riskdesk.domain.orderflow.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class DeltaOscillatorTest {

    private DeltaOscillator oscillator;

    @BeforeEach
    void setUp() {
        // Default: fast=3, slow=10, neutralThreshold=0.5
        oscillator = new DeltaOscillator();
    }

    // =================== Initial state ===================

    @Test
    void initialBias_isNeutral() {
        assertThat(oscillator.bias()).isEqualTo("NEUTRAL");
    }

    @Test
    void firstCompute_seedsBothEmas_returnsZero() {
        // First value seeds both fast and slow EMA to the same value
        // oscillator = fastEma - slowEma = value - value = 0
        double result = oscillator.compute(10.0);
        assertThat(result).isCloseTo(0.0, within(0.001));
    }

    // =================== Consistent positive delta => BULLISH ===================

    @Test
    void consistentPositiveDelta_oscillatorPositive_biasBullish() {
        // Feed many positive delta values; fast EMA should lead slow EMA
        for (int i = 0; i < 30; i++) {
            oscillator.compute(10.0);
        }

        // After convergence, both EMAs approach 10.0, oscillator -> 0
        // But if we now feed a larger value, fast reacts faster
        oscillator.compute(20.0);
        double val = oscillator.compute(20.0);

        // Fast EMA reacts faster to 20 than slow EMA => positive oscillator
        assertThat(val).isGreaterThan(0.0);
        assertThat(oscillator.bias()).isEqualTo("BULLISH");
    }

    @Test
    void risingDelta_pushesOscillatorPositive() {
        // Seed
        oscillator.compute(0.0);
        // Now feed increasing positive deltas
        double lastVal = 0;
        for (int i = 1; i <= 10; i++) {
            lastVal = oscillator.compute(i * 5.0);
        }
        // Fast EMA tracks rising values faster => positive oscillator
        assertThat(lastVal).isGreaterThan(0.0);
        assertThat(oscillator.bias()).isEqualTo("BULLISH");
    }

    // =================== Consistent negative delta => BEARISH ===================

    @Test
    void consistentNegativeDelta_oscillatorNegative_biasBearish() {
        // Feed many negative delta values, then a bigger negative push
        for (int i = 0; i < 30; i++) {
            oscillator.compute(-10.0);
        }
        oscillator.compute(-20.0);
        double val = oscillator.compute(-20.0);

        // Fast reacts faster to -20 => oscillator becomes more negative
        assertThat(val).isLessThan(0.0);
        assertThat(oscillator.bias()).isEqualTo("BEARISH");
    }

    @Test
    void fallingDelta_pushesOscillatorNegative() {
        oscillator.compute(0.0);
        double lastVal = 0;
        for (int i = 1; i <= 10; i++) {
            lastVal = oscillator.compute(-i * 5.0);
        }
        assertThat(lastVal).isLessThan(0.0);
        assertThat(oscillator.bias()).isEqualTo("BEARISH");
    }

    // =================== Small delta => NEUTRAL ===================

    @Test
    void smallDelta_biasNeutral() {
        // Feed very small values around zero
        oscillator.compute(0.1);
        oscillator.compute(-0.1);
        oscillator.compute(0.1);
        oscillator.compute(-0.1);
        oscillator.compute(0.0);

        assertThat(oscillator.bias()).isEqualTo("NEUTRAL");
    }

    @Test
    void constantDelta_convergesTo_zero() {
        // When feeding the same value, both EMAs converge to that value
        // and oscillator approaches 0
        for (int i = 0; i < 100; i++) {
            oscillator.compute(5.0);
        }
        double val = oscillator.compute(5.0);
        // Should be very close to 0 since both EMAs converge to 5.0
        assertThat(val).isCloseTo(0.0, within(0.01));
        assertThat(oscillator.bias()).isEqualTo("NEUTRAL");
    }

    // =================== Reset ===================

    @Test
    void reset_clearsState() {
        // Build up some state
        for (int i = 0; i < 20; i++) {
            oscillator.compute(50.0);
        }
        oscillator.compute(100.0);
        assertThat(oscillator.bias()).isEqualTo("BULLISH");

        oscillator.reset();

        // After reset, bias should be neutral again
        assertThat(oscillator.bias()).isEqualTo("NEUTRAL");
    }

    @Test
    void reset_nextComputeReseeds() {
        oscillator.compute(100.0);
        oscillator.compute(200.0);

        oscillator.reset();

        // First compute after reset seeds both EMAs to the value
        double val = oscillator.compute(5.0);
        assertThat(val).isCloseTo(0.0, within(0.001));
    }

    // =================== Custom parameters ===================

    @Test
    void customPeriods_workCorrectly() {
        DeltaOscillator custom = new DeltaOscillator(2, 5, 1.0);

        // Seed
        custom.compute(0.0);

        // Feed rising values
        for (int i = 1; i <= 10; i++) {
            custom.compute(i * 10.0);
        }

        assertThat(custom.bias()).isEqualTo("BULLISH");
    }

    @Test
    void customNeutralThreshold_affectsBiasClassification() {
        // High threshold => harder to leave NEUTRAL
        DeltaOscillator highThreshold = new DeltaOscillator(3, 10, 100.0);

        highThreshold.compute(0.0);
        for (int i = 0; i < 5; i++) {
            highThreshold.compute(10.0);
        }

        // Even with positive oscillator, if it's < 100, it's still NEUTRAL
        assertThat(highThreshold.bias()).isEqualTo("NEUTRAL");
    }

    // =================== Validation ===================

    @Test
    void invalidFastPeriod_throws() {
        assertThatThrownBy(() -> new DeltaOscillator(0, 10, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fastPeriod");
    }

    @Test
    void slowPeriod_mustBeGreaterThanFast() {
        assertThatThrownBy(() -> new DeltaOscillator(5, 5, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("slowPeriod");
    }

    @Test
    void slowPeriod_lessThanFast_throws() {
        assertThatThrownBy(() -> new DeltaOscillator(10, 3, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("slowPeriod");
    }

    @Test
    void negativeNeutralThreshold_throws() {
        assertThatThrownBy(() -> new DeltaOscillator(3, 10, -1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neutralThreshold");
    }

    // =================== EMA math verification ===================

    @Test
    void emaFormula_computesCorrectly() {
        // fast=3 => multiplier = 2/(3+1) = 0.5
        // slow=10 => multiplier = 2/(10+1) = 2/11 ~= 0.1818
        DeltaOscillator osc = new DeltaOscillator(3, 10, 0.5);

        // First value seeds both EMAs to 10
        double v1 = osc.compute(10.0);
        assertThat(v1).isCloseTo(0.0, within(0.001));

        // Second value: 20
        // fastEma = 20 * 0.5 + 10 * 0.5 = 15.0
        // slowEma = 20 * (2/11) + 10 * (9/11) = 40/11 + 90/11 = 130/11 ~= 11.818
        // oscillator = 15 - 11.818 ~= 3.182
        double v2 = osc.compute(20.0);
        assertThat(v2).isCloseTo(3.182, within(0.01));
    }
}
