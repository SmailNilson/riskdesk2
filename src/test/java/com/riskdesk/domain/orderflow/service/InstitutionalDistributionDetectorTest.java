package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal.AbsorptionSide;
import com.riskdesk.domain.orderflow.model.DistributionSignal;
import com.riskdesk.domain.orderflow.model.DistributionSignal.DistributionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InstitutionalDistributionDetectorTest {

    private final Instrument instrument = Instrument.MNQ;
    private InstitutionalDistributionDetector detector;
    private Instant t0;

    @BeforeEach
    void setUp() {
        // min count 5, min avg score 3.0, TTL 15min, gap 30s
        detector = new InstitutionalDistributionDetector(
            instrument, 5, 3.0, Duration.ofMinutes(15), Duration.ofSeconds(30));
        t0 = Instant.parse("2026-04-23T17:00:00Z");
    }

    private AbsorptionSignal bear(int offsetSeconds, double score) {
        return new AbsorptionSignal(instrument, AbsorptionSide.BEARISH_ABSORPTION,
            score, 700, 2.0, 9000, t0.plusSeconds(offsetSeconds));
    }

    private AbsorptionSignal bull(int offsetSeconds, double score) {
        return new AbsorptionSignal(instrument, AbsorptionSide.BULLISH_ABSORPTION,
            score, -250, 1.0, 5000, t0.plusSeconds(offsetSeconds));
    }

    @Test
    void belowMinCount_returnsEmpty() {
        for (int i = 0; i < 4; i++) {
            Optional<DistributionSignal> out = detector.onAbsorption(
                bear(i * 5, 5.0), 27100.0, null, t0.plusSeconds(i * 5));
            assertThat(out).isEmpty();
        }
    }

    @Test
    void fiveConsecutiveBearishHighScore_firesDistribution() {
        Optional<DistributionSignal> fired = Optional.empty();
        for (int i = 0; i < 5; i++) {
            Optional<DistributionSignal> out = detector.onAbsorption(
                bear(i * 5, 5.0), 27100.0, 27155.0, t0.plusSeconds(i * 5));
            if (out.isPresent()) fired = out;
        }

        assertThat(fired).isPresent();
        DistributionSignal ds = fired.get();
        assertThat(ds.type()).isEqualTo(DistributionType.DISTRIBUTION);
        assertThat(ds.consecutiveCount()).isEqualTo(5);
        assertThat(ds.avgScore()).isEqualTo(5.0);
        assertThat(ds.priceAtDetection()).isEqualTo(27100.0);
        assertThat(ds.resistanceLevel()).isEqualTo(27155.0);
        assertThat(ds.confidenceScore()).isBetween(50, 100);
    }

    @Test
    void avgScoreBelowThreshold_doesNotFire() {
        // 5 events but average = 2.0 < 3.0 threshold
        Optional<DistributionSignal> fired = Optional.empty();
        for (int i = 0; i < 5; i++) {
            Optional<DistributionSignal> out = detector.onAbsorption(
                bear(i * 5, 2.0), 27100.0, null, t0.plusSeconds(i * 5));
            if (out.isPresent()) fired = out;
        }
        assertThat(fired).isEmpty();
    }

    @Test
    void oppositeSide_resetsStreak() {
        // 4 bearish then 1 bullish → bearish streak cleared
        for (int i = 0; i < 4; i++) {
            detector.onAbsorption(bear(i * 5, 5.0), 27100.0, null, t0.plusSeconds(i * 5));
        }
        detector.onAbsorption(bull(20, 5.0), 27100.0, null, t0.plusSeconds(20));

        // Next bearish should not complete to 5 (streak was wiped)
        Optional<DistributionSignal> out = detector.onAbsorption(
            bear(25, 5.0), 27100.0, null, t0.plusSeconds(25));
        assertThat(out).isEmpty();
    }

    @Test
    void interEventGapExceeded_resetsStreak() {
        // 4 events 5s apart, then a 60s gap, then another event → streak broken
        for (int i = 0; i < 4; i++) {
            detector.onAbsorption(bear(i * 5, 5.0), 27100.0, null, t0.plusSeconds(i * 5));
        }
        Optional<DistributionSignal> out = detector.onAbsorption(
            bear(200, 5.0), 27100.0, null, t0.plusSeconds(200));
        assertThat(out).isEmpty();
    }

    @Test
    void staleEventsEvictedFromWindow() {
        // Push 5 bearish 5s apart, but evaluate after 20 minutes → all stale, window empty
        for (int i = 0; i < 5; i++) {
            detector.onAbsorption(bear(i * 5, 5.0),
                27100.0, null, t0.plusSeconds(i * 5));
        }
        // Cooldown already fired on 5th — reset and re-test eviction behavior independently
        detector.reset();

        // Feed 4 at t0, then one far in the future → previous 4 are evicted
        for (int i = 0; i < 4; i++) {
            detector.onAbsorption(bear(i * 5, 5.0), 27100.0, null, t0.plusSeconds(i * 5));
        }
        Instant far = t0.plus(Duration.ofMinutes(20));
        Optional<DistributionSignal> out = detector.onAbsorption(
            new AbsorptionSignal(instrument, AbsorptionSide.BEARISH_ABSORPTION,
                5.0, 700, 2.0, 9000, far),
            27100.0, null, far);
        assertThat(out).isEmpty();
    }

    @Test
    void cooldownPreventsImmediateRefire() {
        Optional<DistributionSignal> first = Optional.empty();
        for (int i = 0; i < 5; i++) {
            Optional<DistributionSignal> r = detector.onAbsorption(
                bear(i * 5, 5.0), 27100.0, null, t0.plusSeconds(i * 5));
            if (r.isPresent()) first = r;
        }
        assertThat(first).isPresent();

        // Feed another event immediately — should NOT fire again
        Optional<DistributionSignal> second = detector.onAbsorption(
            bear(25, 5.0), 27100.0, null, t0.plusSeconds(25));
        assertThat(second).isEmpty();
    }

    @Test
    void accumulationMirrorFires() {
        Optional<DistributionSignal> fired = Optional.empty();
        for (int i = 0; i < 5; i++) {
            Optional<DistributionSignal> out = detector.onAbsorption(
                bull(i * 5, 4.5), 26900.0, 26865.0, t0.plusSeconds(i * 5));
            if (out.isPresent()) fired = out;
        }

        assertThat(fired).isPresent();
        assertThat(fired.get().type()).isEqualTo(DistributionType.ACCUMULATION);
    }

    @Test
    void mnqScenario_firesOnceAtThreshold() {
        // Tonight's MNQ observation: 10 bearish events, avg ~5.0.
        // Detector fires on the 5th event (threshold reached) and cooldowns — so
        // the emitted signal reflects count=5, not 10. Confidence for a just-crossed
        // threshold with strong score is in the 60-80 range (score bonus maxed,
        // duration bonus minimal because fire happens after 20s).
        Optional<DistributionSignal> fired = Optional.empty();
        for (int i = 0; i < 10; i++) {
            Optional<DistributionSignal> out = detector.onAbsorption(
                bear(i * 5, 5.0), 27100.0, 27155.0, t0.plusSeconds(i * 5));
            if (out.isPresent()) fired = out;
        }
        assertThat(fired).isPresent();
        assertThat(fired.get().consecutiveCount()).isEqualTo(5);
        assertThat(fired.get().avgScore()).isEqualTo(5.0);
        assertThat(fired.get().confidenceScore()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void confidenceIsBounded() {
        // Extreme parameters shouldn't exceed 100
        Optional<DistributionSignal> fired = Optional.empty();
        for (int i = 0; i < 50; i++) {
            Optional<DistributionSignal> out = detector.onAbsorption(
                bear(i * 5, 20.0), 27100.0, null, t0.plusSeconds(i * 5));
            if (out.isPresent()) fired = out;
        }
        assertThat(fired).isPresent();
        assertThat(fired.get().confidenceScore()).isBetween(0, 100);
    }
}
