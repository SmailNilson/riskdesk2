package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.SpoofingSignal;
import com.riskdesk.domain.orderflow.model.SpoofingSignal.SpoofSide;
import com.riskdesk.domain.orderflow.model.WallEvent;
import com.riskdesk.domain.orderflow.model.WallEvent.WallEventType;
import com.riskdesk.domain.orderflow.model.WallEvent.WallSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpoofingDetectorTest {

    private SpoofingDetector detector;
    private final Instrument instrument = Instrument.MGC;
    private final Instant now = Instant.now();
    private final double avgLevelSize = 10.0;

    @BeforeEach
    void setUp() {
        detector = new SpoofingDetector();
    }

    @Test
    void noDisappearedEvents_returnsEmptyList() {
        // Only an APPEARED event, no DISAPPEARED to pair with
        List<WallEvent> events = List.of(
                new WallEvent(instrument, WallSide.BID, 2000.0, 50, now.minusSeconds(5), WallEventType.APPEARED)
        );

        List<SpoofingSignal> signals = detector.evaluate(instrument, events, 1999.0, avgLevelSize, now);

        assertThat(signals).isEmpty();
    }

    @Test
    void nullEvents_returnsEmptyList() {
        List<SpoofingSignal> signals = detector.evaluate(instrument, null, 2000.0, avgLevelSize, now);
        assertThat(signals).isEmpty();
    }

    @Test
    void emptyEvents_returnsEmptyList() {
        List<SpoofingSignal> signals = detector.evaluate(instrument, List.of(), 2000.0, avgLevelSize, now);
        assertThat(signals).isEmpty();
    }

    @Test
    void zeroAvgLevelSize_returnsEmptyList() {
        List<WallEvent> events = List.of(
                new WallEvent(instrument, WallSide.BID, 2000.0, 50, now.minusSeconds(5), WallEventType.APPEARED),
                new WallEvent(instrument, WallSide.BID, 2000.0, 0, now.minusSeconds(2), WallEventType.DISAPPEARED)
        );

        List<SpoofingSignal> signals = detector.evaluate(instrument, events, 1999.0, 0.0, now);

        assertThat(signals).isEmpty();
    }

    @Test
    void wallAppearsAndDisappearsFast_spoofDetected() {
        // Wall appears at t-3s, disappears at t-1s => duration 2s (< 10s)
        // Size = 50 > 3 * 10 = 30 => qualifies
        // Score = (50/10) * (1/max(2, 0.5)) * 1.0 = 5.0 * 0.5 * 1.0 = 2.5 > 1.0
        Instant appeared = now.minusSeconds(3);
        Instant disappeared = now.minusSeconds(1);

        List<WallEvent> events = List.of(
                new WallEvent(instrument, WallSide.BID, 2000.0, 50, appeared, WallEventType.APPEARED),
                new WallEvent(instrument, WallSide.BID, 2000.0, 0, disappeared, WallEventType.DISAPPEARED)
        );

        List<SpoofingSignal> signals = detector.evaluate(instrument, events, 2001.0, avgLevelSize, now);

        assertThat(signals).hasSize(1);
        SpoofingSignal signal = signals.get(0);
        assertThat(signal.side()).isEqualTo(SpoofSide.BID_SPOOF);
        assertThat(signal.priceLevel()).isEqualTo(2000.0);
        assertThat(signal.wallSize()).isEqualTo(50);
        assertThat(signal.durationSeconds()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(signal.spoofScore()).isGreaterThan(1.0);
    }

    @Test
    void wallPersistsLong_noSpoofDetected() {
        // Wall appears at t-15s, disappears at t-2s => duration 13s (> 10s max)
        Instant appeared = now.minusSeconds(15);
        Instant disappeared = now.minusSeconds(2);

        List<WallEvent> events = List.of(
                new WallEvent(instrument, WallSide.ASK, 2010.0, 50, appeared, WallEventType.APPEARED),
                new WallEvent(instrument, WallSide.ASK, 2010.0, 0, disappeared, WallEventType.DISAPPEARED)
        );

        List<SpoofingSignal> signals = detector.evaluate(instrument, events, 2005.0, avgLevelSize, now);

        assertThat(signals).isEmpty();
    }

    @Test
    void priceCrossedAfterDisappearance_higherScore() {
        // BID wall at 2000, current price 1999 (below bid wall) => priceCrossed = true => 2x multiplier
        Instant appeared = now.minusSeconds(3);
        Instant disappeared = now.minusSeconds(1);

        List<WallEvent> events = List.of(
                new WallEvent(instrument, WallSide.BID, 2000.0, 50, appeared, WallEventType.APPEARED),
                new WallEvent(instrument, WallSide.BID, 2000.0, 0, disappeared, WallEventType.DISAPPEARED)
        );

        // Without price crossing
        List<SpoofingSignal> noCross = detector.evaluate(instrument, events, 2001.0, avgLevelSize, now);
        // With price crossing (current price below bid wall)
        List<SpoofingSignal> withCross = detector.evaluate(instrument, events, 1999.0, avgLevelSize, now);

        assertThat(noCross).hasSize(1);
        assertThat(withCross).hasSize(1);
        // The crossed version should have exactly 2x the score
        assertThat(withCross.get(0).spoofScore()).isCloseTo(noCross.get(0).spoofScore() * 2.0,
                org.assertj.core.data.Offset.offset(0.01));
        assertThat(withCross.get(0).priceCrossed()).isTrue();
        assertThat(noCross.get(0).priceCrossed()).isFalse();
    }

    @Test
    void askWall_priceCrossedWhenAbove() {
        // ASK wall at 2010, current price 2015 (above ask wall) => priceCrossed = true
        Instant appeared = now.minusSeconds(3);
        Instant disappeared = now.minusSeconds(1);

        List<WallEvent> events = List.of(
                new WallEvent(instrument, WallSide.ASK, 2010.0, 50, appeared, WallEventType.APPEARED),
                new WallEvent(instrument, WallSide.ASK, 2010.0, 0, disappeared, WallEventType.DISAPPEARED)
        );

        List<SpoofingSignal> signals = detector.evaluate(instrument, events, 2015.0, avgLevelSize, now);

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).side()).isEqualTo(SpoofSide.ASK_SPOOF);
        assertThat(signals.get(0).priceCrossed()).isTrue();
    }

    @Test
    void scoreBelowThreshold_filteredOut() {
        // Small wall size: 31 (just over 3 * 10 = 30)
        // Duration = 9s
        // Score = (31/10) * (1/max(9, 0.5)) * 1.0 = 3.1 * 0.111 = 0.344 < 1.0
        Instant appeared = now.minusSeconds(9);
        Instant disappeared = now.minusMillis(1);

        List<WallEvent> events = List.of(
                new WallEvent(instrument, WallSide.BID, 2000.0, 31, appeared, WallEventType.APPEARED),
                new WallEvent(instrument, WallSide.BID, 2000.0, 0, disappeared, WallEventType.DISAPPEARED)
        );

        List<SpoofingSignal> signals = detector.evaluate(instrument, events, 2001.0, avgLevelSize, now);

        assertThat(signals).isEmpty();
    }

    @Test
    void wallTooSmall_filteredOut() {
        // Size = 25 < 3 * 10 = 30 => not a wall candidate
        Instant appeared = now.minusSeconds(2);
        Instant disappeared = now.minusSeconds(1);

        List<WallEvent> events = List.of(
                new WallEvent(instrument, WallSide.BID, 2000.0, 25, appeared, WallEventType.APPEARED),
                new WallEvent(instrument, WallSide.BID, 2000.0, 0, disappeared, WallEventType.DISAPPEARED)
        );

        List<SpoofingSignal> signals = detector.evaluate(instrument, events, 1999.0, avgLevelSize, now);

        assertThat(signals).isEmpty();
    }

    @Test
    void multipleSpoofs_allDetected() {
        Instant t0 = now.minusSeconds(8);
        Instant t1 = now.minusSeconds(6);
        Instant t2 = now.minusSeconds(4);
        Instant t3 = now.minusSeconds(2);

        List<WallEvent> events = List.of(
                new WallEvent(instrument, WallSide.BID, 2000.0, 50, t0, WallEventType.APPEARED),
                new WallEvent(instrument, WallSide.BID, 2000.0, 0, t1, WallEventType.DISAPPEARED),
                new WallEvent(instrument, WallSide.ASK, 2010.0, 60, t2, WallEventType.APPEARED),
                new WallEvent(instrument, WallSide.ASK, 2010.0, 0, t3, WallEventType.DISAPPEARED)
        );

        List<SpoofingSignal> signals = detector.evaluate(instrument, events, 1999.0, avgLevelSize, now);

        assertThat(signals).hasSize(2);
        assertThat(signals.get(0).side()).isEqualTo(SpoofSide.BID_SPOOF);
        assertThat(signals.get(1).side()).isEqualTo(SpoofSide.ASK_SPOOF);
    }

    @Test
    void mismatchedSide_noPairing() {
        // APPEARED BID but only DISAPPEARED ASK => no match
        List<WallEvent> events = List.of(
                new WallEvent(instrument, WallSide.BID, 2000.0, 50, now.minusSeconds(3), WallEventType.APPEARED),
                new WallEvent(instrument, WallSide.ASK, 2000.0, 0, now.minusSeconds(1), WallEventType.DISAPPEARED)
        );

        List<SpoofingSignal> signals = detector.evaluate(instrument, events, 1999.0, avgLevelSize, now);

        assertThat(signals).isEmpty();
    }

    @Test
    void mismatchedPrice_noPairing() {
        // Same side but different prices => no match
        List<WallEvent> events = List.of(
                new WallEvent(instrument, WallSide.BID, 2000.0, 50, now.minusSeconds(3), WallEventType.APPEARED),
                new WallEvent(instrument, WallSide.BID, 2001.0, 0, now.minusSeconds(1), WallEventType.DISAPPEARED)
        );

        List<SpoofingSignal> signals = detector.evaluate(instrument, events, 1999.0, avgLevelSize, now);

        assertThat(signals).isEmpty();
    }

    @Test
    void veryFastDisappearance_highScore_dueToMinDurationFloor() {
        // Duration < 0.5s => clamped at 0.5 for calculation
        // Score = (50/10) * (1/0.5) * 1.0 = 5.0 * 2.0 = 10.0
        Instant appeared = now.minusMillis(200);
        Instant disappeared = now.minusMillis(100);

        List<WallEvent> events = List.of(
                new WallEvent(instrument, WallSide.BID, 2000.0, 50, appeared, WallEventType.APPEARED),
                new WallEvent(instrument, WallSide.BID, 2000.0, 0, disappeared, WallEventType.DISAPPEARED)
        );

        List<SpoofingSignal> signals = detector.evaluate(instrument, events, 2001.0, avgLevelSize, now);

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).spoofScore()).isGreaterThan(5.0);
    }

    @Test
    void signalTimestamp_matchesNowParameter() {
        List<WallEvent> events = List.of(
                new WallEvent(instrument, WallSide.BID, 2000.0, 50, now.minusSeconds(3), WallEventType.APPEARED),
                new WallEvent(instrument, WallSide.BID, 2000.0, 0, now.minusSeconds(1), WallEventType.DISAPPEARED)
        );

        Instant evalTime = Instant.now().plusSeconds(100);
        List<SpoofingSignal> signals = detector.evaluate(instrument, events, 2001.0, avgLevelSize, evalTime);

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).timestamp()).isEqualTo(evalTime);
    }
}
