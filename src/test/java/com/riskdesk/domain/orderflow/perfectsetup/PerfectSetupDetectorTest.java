package com.riskdesk.domain.orderflow.perfectsetup;

import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link PerfectSetupDetector}: per-axis scoring, direction
 * resolution, the 4/6 arm threshold, the R:R hard gate, and the
 * IDLE→ARMED→TRIGGERED/INVALIDATED/EXPIRED state machine with cooldown.
 */
class PerfectSetupDetectorTest {

    private final PerfectSetupDetector detector = new PerfectSetupDetector();
    private final PerfectSetupThresholds t = PerfectSetupThresholds.defaults();
    private final Instant now = Instant.parse("2026-05-29T15:00:00Z");

    // ─── Builder for readable scenarios (MNQ, tick 0.25) ──────────────────

    private static final class In {
        Double price = 30305.25;
        double tick = 0.25;
        Double atr = 40.0;
        String distType, cycleType, absDominant;
        Integer distConf;
        double absMaxScore = 0.0;
        List<Long> absMags = List.of();
        IcebergContext iceberg = IcebergContext.empty();
        FlashCrashContext flash = FlashCrashContext.none();
        Double vwap = 30299.42, vwapLower = 30270.0, vwapUpper = 30360.0, bbPct = 0.5;

        PerfectSetupInputs at(Instant ts) {
            return new PerfectSetupInputs(Instrument.MNQ, price, tick, atr, distType, distConf,
                cycleType, absDominant, absMaxScore, absMags, iceberg, flash,
                vwap, vwapLower, vwapUpper, bbPct, ts);
        }
    }

    /** A full 6/6 LONG confluence. */
    private In fullLong() {
        In in = new In();
        in.distType = "ACCUMULATION"; in.distConf = 84; in.cycleType = "BULLISH_CYCLE";
        in.absDominant = "BULL"; in.absMaxScore = 9.0; in.absMags = List.of(60L, 250L, 1400L);
        in.iceberg = new IcebergContext(30298.75, 100.0, 9, 30367.25, 60.0, 4);
        in.flash = new FlashCrashContext("REVERSING", 100.0);
        in.bbPct = 0.10; // at lower band
        return in;
    }

    // ─── Arming ───────────────────────────────────────────────────────────

    @Test
    void fullConfluence_armsLong() {
        PerfectSetupSignal s = detector.evaluate(fullLong().at(now), null, t);

        assertThat(s.direction()).isEqualTo(PerfectSetupDirection.LONG);
        assertThat(s.state()).isEqualTo(PerfectSetupState.LONG_ARMED);
        assertThat(s.score()).isEqualTo(6);
        assertThat(s.riskReward()).isGreaterThanOrEqualTo(t.minRR());
        assertThat(s.entryLow()).isNotNull();
        assertThat(s.stop()).isLessThan(s.entryLow());
        assertThat(s.tp1()).isGreaterThan(s.entryHigh());
    }

    @Test
    void exactlyFourAxes_armsAtThreshold() {
        In in = fullLong();
        in.distType = null; in.distConf = null;       // drop REGIME
        in.flash = FlashCrashContext.none();           // drop LIQUIDITY_GRAB
        // remaining: ICEBERG, ABSORPTION, VALUE, RISK_REWARD = 4

        PerfectSetupSignal s = detector.evaluate(in.at(now), null, t);

        assertThat(s.score()).isEqualTo(4);
        assertThat(s.state()).isEqualTo(PerfectSetupState.LONG_ARMED);
    }

    @Test
    void belowThreshold_staysIdle() {
        In in = fullLong();
        in.distType = null; in.distConf = null;       // drop REGIME
        in.flash = FlashCrashContext.none();           // drop LIQUIDITY_GRAB
        in.bbPct = 0.5; in.vwap = 30200.0;             // drop VALUE (price > vwap, %B mid)
        // remaining: ICEBERG, ABSORPTION, RISK_REWARD = 3

        PerfectSetupSignal s = detector.evaluate(in.at(now), null, t);

        assertThat(s.score()).isEqualTo(3);
        assertThat(s.state()).isEqualTo(PerfectSetupState.IDLE);
        assertThat(s.direction()).isEqualTo(PerfectSetupDirection.NONE);
    }

    @Test
    void goodConfluenceButPoorRiskReward_doesNotArm() {
        In in = new In();
        in.distType = "ACCUMULATION"; in.distConf = 84;     // REGIME
        in.absDominant = "BULL"; in.absMaxScore = 9.0;       // ABSORPTION
        in.bbPct = 0.10;                                     // VALUE
        in.atr = 2.0;                                        // tiny range
        in.iceberg = new IcebergContext(30304.0, 100.0, 9, 30306.0, 60.0, 3); // ICEBERG, resistance very close
        // 4 axes pass but R:R will be < 2 → no arm

        PerfectSetupSignal s = detector.evaluate(in.at(now), null, t);

        assertThat(s.riskReward()).isNull(); // idle signal carries no plan
        assertThat(s.state()).isEqualTo(PerfectSetupState.IDLE);
        assertThat(s.reasoning()).containsIgnoringCase("R:R");
    }

    @Test
    void distributionConfluence_armsShort() {
        In in = new In();
        in.price = 30360.0;
        in.distType = "DISTRIBUTION"; in.distConf = 80;          // REGIME (short)
        in.absDominant = "BEAR"; in.absMaxScore = 12.0;          // ABSORPTION (short)
        in.bbPct = 0.92;                                         // VALUE (short, upper band)
        in.iceberg = new IcebergContext(30300.0, 70.0, 5, 30362.0, 90.0, 6); // ASK near price
        // ICEBERG + ABSORPTION + VALUE + REGIME + RISK_REWARD → short

        PerfectSetupSignal s = detector.evaluate(in.at(now), null, t);

        assertThat(s.direction()).isEqualTo(PerfectSetupDirection.SHORT);
        assertThat(s.state()).isEqualTo(PerfectSetupState.SHORT_ARMED);
        assertThat(s.score()).isGreaterThanOrEqualTo(4);
        assertThat(s.stop()).isGreaterThan(s.entryHigh());
    }

    @Test
    void tie_staysIdleWithNoDirection() {
        // No order-flow inputs at all → both directions score 0 → tie.
        PerfectSetupSignal s = detector.evaluate(new In().at(now), null, t);

        assertThat(s.direction()).isEqualTo(PerfectSetupDirection.NONE);
        assertThat(s.state()).isEqualTo(PerfectSetupState.IDLE);
    }

    // ─── State machine ─────────────────────────────────────────────────────

    @Test
    void armedThenPriceEntersZone_triggers() {
        PerfectSetupSignal armed = detector.evaluate(fullLong().at(now), null, t);
        assertThat(armed.state()).isEqualTo(PerfectSetupState.LONG_ARMED);

        In next = fullLong();
        next.price = (armed.entryLow() + armed.entryHigh()) / 2.0; // inside the zone
        PerfectSetupSignal s = detector.evaluate(next.at(now.plusSeconds(30)), armed, t);

        assertThat(s.state()).isEqualTo(PerfectSetupState.TRIGGERED);
        assertThat(s.entryLow()).isEqualTo(armed.entryLow()); // plan carried forward
    }

    @Test
    void armedThenStopBreached_invalidates() {
        PerfectSetupSignal armed = detector.evaluate(fullLong().at(now), null, t);

        In next = fullLong();
        next.price = armed.stop() - 5.0; // below the stop
        PerfectSetupSignal s = detector.evaluate(next.at(now.plusSeconds(30)), armed, t);

        assertThat(s.state()).isEqualTo(PerfectSetupState.INVALIDATED);
    }

    @Test
    void armedThenTtlElapses_expires() {
        PerfectSetupSignal armed = detector.evaluate(fullLong().at(now), null, t);

        In next = fullLong();
        next.price = armed.tp1() + 10.0; // out of zone, above stop
        Instant later = now.plusSeconds(t.armTtlSeconds() + 1);
        PerfectSetupSignal s = detector.evaluate(next.at(later), armed, t);

        assertThat(s.state()).isEqualTo(PerfectSetupState.EXPIRED);
    }

    @Test
    void terminalState_heldDuringCooldown_thenReArms() {
        PerfectSetupSignal armed = detector.evaluate(fullLong().at(now), null, t);
        In trig = fullLong();
        trig.price = (armed.entryLow() + armed.entryHigh()) / 2.0;
        Instant tTrig = now.plusSeconds(30);
        PerfectSetupSignal triggered = detector.evaluate(trig.at(tTrig), armed, t);
        assertThat(triggered.state()).isEqualTo(PerfectSetupState.TRIGGERED);

        // During cooldown: still TRIGGERED, timestamp unchanged (no re-arm).
        PerfectSetupSignal held = detector.evaluate(
            fullLong().at(tTrig.plusSeconds(10)), triggered, t);
        assertThat(held.state()).isEqualTo(PerfectSetupState.TRIGGERED);
        assertThat(held.timestamp()).isEqualTo(triggered.timestamp());

        // After cooldown: fresh evaluation re-arms.
        PerfectSetupSignal reArmed = detector.evaluate(
            fullLong().at(tTrig.plusSeconds(t.cooldownSeconds() + 1)), held, t);
        assertThat(reArmed.state()).isEqualTo(PerfectSetupState.LONG_ARMED);
    }

    @Test
    void absorptionAxis_requiresClimaxScore() {
        In in = fullLong();
        in.absMaxScore = 2.0; // below climax min (8) → ABSORPTION fails
        PerfectSetupSignal s = detector.evaluate(in.at(now), null, t);

        PerfectSetupAxis.Result abs = s.axes().stream()
            .filter(a -> a.axis() == PerfectSetupAxis.ABSORPTION).findFirst().orElseThrow();
        assertThat(abs.passed()).isFalse();
    }
}
