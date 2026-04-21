package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VwapRejectionDetector — bearish VWAP rejection on MNQ")
class VwapRejectionDetectorTest {

    private VwapRejectionDetector detector;

    private static final Instant T0 = Instant.parse("2026-04-10T15:00:00Z");
    private static final BigDecimal VWAP = new BigDecimal("24100.00");

    @BeforeEach
    void setUp() {
        detector = new VwapRejectionDetector();
    }

    private Candle mnqCandle(String open, String high, String low, String close) {
        return new Candle(Instrument.MNQ, "5m", T0,
                new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), 1000);
    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        void rejectsNegativeTolerance() {
            assertThatThrownBy(() -> new VwapRejectionDetector(-0.001))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsToleranceAbove5Pct() {
            assertThatThrownBy(() -> new VwapRejectionDetector(0.051))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void acceptsZeroTolerance() {
            assertThat(new VwapRejectionDetector(0)).isNotNull();
        }

        @Test
        void acceptsMaxTolerance() {
            assertThat(new VwapRejectionDetector(0.05)).isNotNull();
        }
    }

    @Nested
    @DisplayName("Guard clauses")
    class GuardClauses {

        @Test
        void nullCandleThrowsNPE() {
            assertThatThrownBy(() -> detector.isRejection(null, VWAP))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void nullVwapThrowsNPE() {
            Candle c = mnqCandle("24090", "24110", "24080", "24095");
            assertThatThrownBy(() -> detector.isRejection(c, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void zeroVwapReturnsFalse() {
            Candle c = mnqCandle("24090", "24110", "24080", "24095");
            assertThat(detector.isRejection(c, BigDecimal.ZERO)).isFalse();
        }

        @Test
        void negativeVwapReturnsFalse() {
            Candle c = mnqCandle("24090", "24110", "24080", "24095");
            assertThat(detector.isRejection(c, new BigDecimal("-100"))).isFalse();
        }
    }

    @Nested
    @DisplayName("Happy path — bearish rejection detected")
    class HappyPath {

        @Test
        void highTouchesVwapAndClosesBelowIt() {
            // High reaches VWAP, close is below VWAP → bearish rejection
            Candle c = mnqCandle("24090", "24100", "24070", "24085");
            assertThat(detector.isRejection(c, VWAP)).isTrue();
        }

        @Test
        void highExceedsVwapAndClosesBelowIt() {
            // High overshoots VWAP, close below → still a rejection
            Candle c = mnqCandle("24090", "24120", "24070", "24080");
            assertThat(detector.isRejection(c, VWAP)).isTrue();
        }

        @Test
        void detectReturnsDetailedResult() {
            Candle c = mnqCandle("24090", "24105", "24070", "24085");
            VwapRejectionDetector.RejectionResult result = detector.detect(c, VWAP);

            assertThat(result).isNotNull();
            assertThat(result.candleClose()).isEqualByComparingTo("24085");
            assertThat(result.vwap()).isEqualByComparingTo("24100.00");
            assertThat(result.candleHigh()).isEqualByComparingTo("24105");
            assertThat(result.distanceBelowPct()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("No rejection conditions")
    class NoRejection {

        @Test
        void highDoesNotReachVwap() {
            // High is well below VWAP — no touch
            Candle c = mnqCandle("24050", "24070", "24040", "24055");
            assertThat(detector.isRejection(c, VWAP)).isFalse();
        }

        @Test
        void closesAboveVwap() {
            // High reaches VWAP but close is above → sellers didn't win
            Candle c = mnqCandle("24090", "24110", "24080", "24105");
            assertThat(detector.isRejection(c, VWAP)).isFalse();
        }

        @Test
        void closesExactlyAtVwap() {
            // Close == VWAP → not strictly below, so no rejection
            Candle c = mnqCandle("24090", "24110", "24080", "24100");
            assertThat(detector.isRejection(c, VWAP)).isFalse();
        }

        @Test
        void detectReturnsNullWhenNoRejection() {
            Candle c = mnqCandle("24050", "24070", "24040", "24055");
            assertThat(detector.detect(c, VWAP)).isNull();
        }
    }

    @Nested
    @DisplayName("Tolerance boundary")
    class ToleranceBoundary {

        @Test
        void highWithinDefaultToleranceCountsAsTouch() {
            // VWAP = 24100, tolerance = 0.1% → threshold = 24100 * 0.999 = 24075.9
            // High = 24076 → just above threshold → counts as touch
            Candle c = mnqCandle("24060", "24076", "24050", "24065");
            assertThat(detector.isRejection(c, VWAP)).isTrue();
        }

        @Test
        void highBelowToleranceThresholdIsNotTouch() {
            // VWAP = 24100, threshold ≈ 24075.9
            // High = 24075 → below threshold → no touch
            Candle c = mnqCandle("24060", "24075", "24050", "24065");
            assertThat(detector.isRejection(c, VWAP)).isFalse();
        }

        @Test
        void zeroToleranceRequiresExactTouch() {
            var strict = new VwapRejectionDetector(0);
            // High exactly at VWAP → touch
            Candle touch = mnqCandle("24090", "24100", "24070", "24085");
            assertThat(strict.isRejection(touch, VWAP)).isTrue();

            // High = 24099.99 → no touch with zero tolerance
            Candle miss = mnqCandle("24090", "24099.99", "24070", "24085");
            assertThat(strict.isRejection(miss, VWAP)).isFalse();
        }
    }

    @Nested
    @DisplayName("Distance calculation")
    class DistanceCalculation {

        @Test
        void distanceBelowPctIsPositiveWhenCloseBelowVwap() {
            Candle c = mnqCandle("24090", "24105", "24070", "24050");
            var result = detector.detect(c, VWAP);
            assertThat(result).isNotNull();
            assertThat(result.distanceBelowPct()).isGreaterThan(0);
        }
    }
}
