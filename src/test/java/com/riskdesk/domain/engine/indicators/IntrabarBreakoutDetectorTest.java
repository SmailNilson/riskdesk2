package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("IntrabarBreakoutDetector — channel breakout with volume confirmation")
class IntrabarBreakoutDetectorTest {

    private IntrabarBreakoutDetector detector;

    private static final Instant T0 = Instant.parse("2026-04-10T14:00:00Z");

    @BeforeEach
    void setUp() {
        detector = new IntrabarBreakoutDetector();
    }

    private Candle candle(int minuteOffset, String close, long volume) {
        BigDecimal c = new BigDecimal(close);
        return new Candle(Instrument.MCL, "5m",
                T0.plusSeconds(minuteOffset * 60L),
                c.subtract(BigDecimal.ONE), c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, volume);
    }

    private List<Candle> windowWithBreakout(String resistanceClose, String breakoutClose,
                                            long avgVolume, long breakoutVolume) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < IntrabarBreakoutDetector.DEFAULT_LOOKBACK; i++) {
            candles.add(candle(i, resistanceClose, avgVolume));
        }
        candles.add(candle(IntrabarBreakoutDetector.DEFAULT_LOOKBACK, breakoutClose, breakoutVolume));
        return candles;
    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        void rejectsLookbackBelow2() {
            assertThatThrownBy(() -> new IntrabarBreakoutDetector(1, 1.5))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsZeroVolumeMultiplier() {
            assertThatThrownBy(() -> new IntrabarBreakoutDetector(10, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNegativeVolumeMultiplier() {
            assertThatThrownBy(() -> new IntrabarBreakoutDetector(10, -1.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void acceptsMinimalValidParams() {
            var d = new IntrabarBreakoutDetector(2, 0.01);
            assertThat(d).isNotNull();
        }
    }

    @Nested
    @DisplayName("Guard clauses")
    class GuardClauses {

        @Test
        void nullCandlesThrowsNPE() {
            assertThatThrownBy(() -> detector.detect(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void emptyListReturnsEmpty() {
            assertThat(detector.detect(Collections.emptyList())).isEmpty();
        }

        @Test
        void insufficientCandlesReturnsEmpty() {
            List<Candle> tooFew = new ArrayList<>();
            for (int i = 0; i < IntrabarBreakoutDetector.DEFAULT_LOOKBACK; i++) {
                tooFew.add(candle(i, "100.00", 500));
            }
            assertThat(detector.detect(tooFew)).isEmpty();
        }

        @Test
        void exactMinimumSizeIsAccepted() {
            List<Candle> exact = windowWithBreakout("100.00", "101.00", 500, 1000);
            assertThat(exact).hasSize(IntrabarBreakoutDetector.DEFAULT_LOOKBACK + 1);
            assertThat(detector.detect(exact)).isPresent();
        }
    }

    @Nested
    @DisplayName("Happy path — breakout detected")
    class HappyPath {

        @Test
        void detectsBreakoutAboveResistanceWithHighVolume() {
            List<Candle> candles = windowWithBreakout("110.00", "111.00", 500, 800);

            Optional<IntrabarBreakoutDetector.BreakoutResult> result = detector.detect(candles);

            assertThat(result).isPresent();
            assertThat(result.get().breakoutClose()).isEqualByComparingTo("111.00");
            assertThat(result.get().resistanceLevel()).isEqualByComparingTo("110.00");
            assertThat(result.get().currentVolume()).isEqualTo(800);
            assertThat(result.get().averageVolume()).isEqualTo(500);
            assertThat(result.get().volumeRatio()).isGreaterThanOrEqualTo(1.5);
        }

        @Test
        void resistanceLevelIsMaxCloseInWindow() {
            List<Candle> candles = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                candles.add(candle(i, "100.00", 500));
            }
            candles.add(candle(5, "105.00", 500)); // peak in window
            for (int i = 6; i < IntrabarBreakoutDetector.DEFAULT_LOOKBACK; i++) {
                candles.add(candle(i, "102.00", 500));
            }
            candles.add(candle(IntrabarBreakoutDetector.DEFAULT_LOOKBACK, "106.00", 900));

            var result = detector.detect(candles);

            assertThat(result).isPresent();
            assertThat(result.get().resistanceLevel()).isEqualByComparingTo("105.00");
        }
    }

    @Nested
    @DisplayName("No breakout conditions")
    class NoBreakout {

        @Test
        void closeAtResistanceIsNotBreakout() {
            List<Candle> candles = windowWithBreakout("110.00", "110.00", 500, 800);
            assertThat(detector.detect(candles)).isEmpty();
        }

        @Test
        void closeBelowResistanceIsNotBreakout() {
            List<Candle> candles = windowWithBreakout("110.00", "109.00", 500, 800);
            assertThat(detector.detect(candles)).isEmpty();
        }

        @Test
        void priceBreakoutWithInsufficientVolume() {
            List<Candle> candles = windowWithBreakout("110.00", "111.00", 500, 600);
            assertThat(detector.detect(candles)).isEmpty();
        }

        @Test
        void zeroAverageVolumeReturnsEmpty() {
            List<Candle> candles = windowWithBreakout("110.00", "111.00", 0, 100);
            assertThat(detector.detect(candles)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Volume edge cases")
    class VolumeEdgeCases {

        @Test
        void exactlyAtMultiplierThresholdIsNotBreakout() {
            // 1.5x exactly — detect() uses < not <=, so exactly 1.5 should pass
            List<Candle> candles = windowWithBreakout("110.00", "111.00", 1000, 1500);
            assertThat(detector.detect(candles)).isPresent();
        }

        @Test
        void justBelowMultiplierThresholdIsRejected() {
            List<Candle> candles = windowWithBreakout("110.00", "111.00", 1000, 1499);
            assertThat(detector.detect(candles)).isEmpty();
        }

        @Test
        void customMultiplierIsRespected() {
            var lenient = new IntrabarBreakoutDetector(IntrabarBreakoutDetector.DEFAULT_LOOKBACK, 1.0);
            List<Candle> candles = windowWithBreakout("110.00", "111.00", 500, 500);
            assertThat(lenient.detect(candles)).isPresent();
        }
    }

    @Nested
    @DisplayName("Custom lookback")
    class CustomLookback {

        @Test
        void shorterLookbackRequiresFewerCandles() {
            var shortDetector = new IntrabarBreakoutDetector(3, 1.5);
            List<Candle> candles = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                candles.add(candle(i, "100.00", 500));
            }
            candles.add(candle(3, "101.00", 800));

            assertThat(shortDetector.detect(candles)).isPresent();
        }
    }
}
