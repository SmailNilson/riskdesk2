package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.IcebergSignal;
import com.riskdesk.domain.orderflow.model.IcebergSignal.IcebergSide;
import com.riskdesk.domain.orderflow.model.WallEvent;
import com.riskdesk.domain.orderflow.model.WallEvent.WallEventType;
import com.riskdesk.domain.orderflow.model.WallEvent.WallSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IcebergDetectorTest {

    private IcebergDetector detector;
    private static final Instrument INSTRUMENT = Instrument.MNQ;
    private static final double TICK_SIZE = 0.25; // MNQ tick size
    private final Instant now = Instant.parse("2025-04-11T14:01:00Z");

    @BeforeEach
    void setUp() {
        detector = new IcebergDetector();
    }

    @Test
    void noEvents_returnsEmpty() {
        List<IcebergSignal> signals = detector.evaluate(INSTRUMENT, List.of(), TICK_SIZE, now);
        assertTrue(signals.isEmpty());
    }

    @Test
    void nullEvents_returnsEmpty() {
        List<IcebergSignal> signals = detector.evaluate(INSTRUMENT, null, TICK_SIZE, now);
        assertTrue(signals.isEmpty());
    }

    @Test
    void tooFewEvents_returnsEmpty() {
        List<WallEvent> events = List.of(
            wallEvent(WallSide.BID, 20000.0, 150, WallEventType.APPEARED, seconds(-10)),
            wallEvent(WallSide.BID, 20000.0, 150, WallEventType.DISAPPEARED, seconds(-5))
        );
        List<IcebergSignal> signals = detector.evaluate(INSTRUMENT, events, TICK_SIZE, now);
        assertTrue(signals.isEmpty());
    }

    @Test
    void twoRecharges_detectsIceberg() {
        // Pattern: APPEARED -> DISAPPEARED -> APPEARED -> DISAPPEARED -> APPEARED
        // = 2 recharge cycles
        List<WallEvent> events = List.of(
            wallEvent(WallSide.BID, 20000.0, 150, WallEventType.APPEARED, seconds(-50)),
            wallEvent(WallSide.BID, 20000.0, 150, WallEventType.DISAPPEARED, seconds(-40)),
            wallEvent(WallSide.BID, 20000.0, 160, WallEventType.APPEARED, seconds(-30)),
            wallEvent(WallSide.BID, 20000.0, 160, WallEventType.DISAPPEARED, seconds(-20)),
            wallEvent(WallSide.BID, 20000.0, 155, WallEventType.APPEARED, seconds(-10))
        );

        List<IcebergSignal> signals = detector.evaluate(INSTRUMENT, events, TICK_SIZE, now);

        assertEquals(1, signals.size());
        IcebergSignal signal = signals.get(0);
        assertEquals(INSTRUMENT, signal.instrument());
        assertEquals(IcebergSide.BID_ICEBERG, signal.side());
        assertEquals(20000.0, signal.priceLevel(), 0.01);
        assertEquals(2, signal.rechargeCount());
        assertTrue(signal.icebergScore() >= 50, "Score should be at least 50 for 2 recharges");
    }

    @Test
    void askSideIceberg_detectedCorrectly() {
        List<WallEvent> events = List.of(
            wallEvent(WallSide.ASK, 20010.0, 200, WallEventType.APPEARED, seconds(-45)),
            wallEvent(WallSide.ASK, 20010.0, 200, WallEventType.DISAPPEARED, seconds(-35)),
            wallEvent(WallSide.ASK, 20010.0, 210, WallEventType.APPEARED, seconds(-25)),
            wallEvent(WallSide.ASK, 20010.0, 210, WallEventType.DISAPPEARED, seconds(-15)),
            wallEvent(WallSide.ASK, 20010.0, 205, WallEventType.APPEARED, seconds(-5))
        );

        List<IcebergSignal> signals = detector.evaluate(INSTRUMENT, events, TICK_SIZE, now);

        assertEquals(1, signals.size());
        assertEquals(IcebergSide.ASK_ICEBERG, signals.get(0).side());
    }

    @Test
    void singleRecharge_notEnough() {
        // Only 1 recharge cycle = below threshold
        List<WallEvent> events = List.of(
            wallEvent(WallSide.BID, 20000.0, 150, WallEventType.APPEARED, seconds(-30)),
            wallEvent(WallSide.BID, 20000.0, 150, WallEventType.DISAPPEARED, seconds(-20)),
            wallEvent(WallSide.BID, 20000.0, 160, WallEventType.APPEARED, seconds(-10))
        );

        List<IcebergSignal> signals = detector.evaluate(INSTRUMENT, events, TICK_SIZE, now);
        assertTrue(signals.isEmpty(), "1 recharge should not trigger detection");
    }

    @Test
    void eventsOutsideWindow_ignored() {
        // All events are older than 60 seconds
        List<WallEvent> events = List.of(
            wallEvent(WallSide.BID, 20000.0, 150, WallEventType.APPEARED, seconds(-120)),
            wallEvent(WallSide.BID, 20000.0, 150, WallEventType.DISAPPEARED, seconds(-110)),
            wallEvent(WallSide.BID, 20000.0, 160, WallEventType.APPEARED, seconds(-100)),
            wallEvent(WallSide.BID, 20000.0, 160, WallEventType.DISAPPEARED, seconds(-90)),
            wallEvent(WallSide.BID, 20000.0, 155, WallEventType.APPEARED, seconds(-80))
        );

        List<IcebergSignal> signals = detector.evaluate(INSTRUMENT, events, TICK_SIZE, now);
        assertTrue(signals.isEmpty(), "Events outside 60s window should be ignored");
    }

    @Test
    void differentPriceLevels_separateGroups() {
        // Events at two different price levels — only one has enough recharges
        List<WallEvent> events = new ArrayList<>();
        // Level 20000 — 2 recharges
        events.add(wallEvent(WallSide.BID, 20000.0, 150, WallEventType.APPEARED, seconds(-50)));
        events.add(wallEvent(WallSide.BID, 20000.0, 150, WallEventType.DISAPPEARED, seconds(-40)));
        events.add(wallEvent(WallSide.BID, 20000.0, 160, WallEventType.APPEARED, seconds(-30)));
        events.add(wallEvent(WallSide.BID, 20000.0, 160, WallEventType.DISAPPEARED, seconds(-20)));
        events.add(wallEvent(WallSide.BID, 20000.0, 155, WallEventType.APPEARED, seconds(-10)));

        // Level 20005 — only 1 recharge (not enough)
        events.add(wallEvent(WallSide.BID, 20005.0, 100, WallEventType.APPEARED, seconds(-30)));
        events.add(wallEvent(WallSide.BID, 20005.0, 100, WallEventType.DISAPPEARED, seconds(-20)));
        events.add(wallEvent(WallSide.BID, 20005.0, 110, WallEventType.APPEARED, seconds(-10)));

        List<IcebergSignal> signals = detector.evaluate(INSTRUMENT, events, TICK_SIZE, now);

        assertEquals(1, signals.size());
        assertEquals(20000.0, signals.get(0).priceLevel(), 0.01);
    }

    @Test
    void highRechargeCount_scoresCapped() {
        // 5 recharges: 5 * 25 = 125, but capped at 100
        List<WallEvent> events = new ArrayList<>();
        Instant base = now.minusSeconds(55);

        for (int i = 0; i < 6; i++) {
            events.add(wallEvent(WallSide.ASK, 20000.0, 300, WallEventType.APPEARED,
                base.plusSeconds(i * 8)));
            if (i < 5) {
                events.add(wallEvent(WallSide.ASK, 20000.0, 300, WallEventType.DISAPPEARED,
                    base.plusSeconds(i * 8 + 4)));
            }
        }

        List<IcebergSignal> signals = detector.evaluate(INSTRUMENT, events, TICK_SIZE, now);

        assertEquals(1, signals.size());
        assertEquals(100.0, signals.get(0).icebergScore(), 0.01, "Score should be capped at 100");
    }

    @Test
    void largeSize_addsBonus() {
        // 2 recharges with size > 200 = 2*25 + 20 = 70
        List<WallEvent> events = List.of(
            wallEvent(WallSide.BID, 20000.0, 250, WallEventType.APPEARED, seconds(-50)),
            wallEvent(WallSide.BID, 20000.0, 250, WallEventType.DISAPPEARED, seconds(-40)),
            wallEvent(WallSide.BID, 20000.0, 260, WallEventType.APPEARED, seconds(-30)),
            wallEvent(WallSide.BID, 20000.0, 260, WallEventType.DISAPPEARED, seconds(-20)),
            wallEvent(WallSide.BID, 20000.0, 255, WallEventType.APPEARED, seconds(-10))
        );

        List<IcebergSignal> signals = detector.evaluate(INSTRUMENT, events, TICK_SIZE, now);

        assertEquals(1, signals.size());
        assertEquals(70.0, signals.get(0).icebergScore(), 0.01);
    }

    @Test
    void mixedSides_separateDetection() {
        List<WallEvent> events = new ArrayList<>();
        // BID iceberg — 2 recharges
        events.add(wallEvent(WallSide.BID, 20000.0, 150, WallEventType.APPEARED, seconds(-50)));
        events.add(wallEvent(WallSide.BID, 20000.0, 150, WallEventType.DISAPPEARED, seconds(-40)));
        events.add(wallEvent(WallSide.BID, 20000.0, 160, WallEventType.APPEARED, seconds(-30)));
        events.add(wallEvent(WallSide.BID, 20000.0, 160, WallEventType.DISAPPEARED, seconds(-20)));
        events.add(wallEvent(WallSide.BID, 20000.0, 155, WallEventType.APPEARED, seconds(-10)));

        // ASK iceberg — 2 recharges at different price
        events.add(wallEvent(WallSide.ASK, 20010.0, 200, WallEventType.APPEARED, seconds(-50)));
        events.add(wallEvent(WallSide.ASK, 20010.0, 200, WallEventType.DISAPPEARED, seconds(-40)));
        events.add(wallEvent(WallSide.ASK, 20010.0, 210, WallEventType.APPEARED, seconds(-30)));
        events.add(wallEvent(WallSide.ASK, 20010.0, 210, WallEventType.DISAPPEARED, seconds(-20)));
        events.add(wallEvent(WallSide.ASK, 20010.0, 205, WallEventType.APPEARED, seconds(-10)));

        List<IcebergSignal> signals = detector.evaluate(INSTRUMENT, events, TICK_SIZE, now);

        assertEquals(2, signals.size());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Instant seconds(int offsetFromNow) {
        return now.plusSeconds(offsetFromNow);
    }

    private WallEvent wallEvent(WallSide side, double price, long size,
                                WallEventType type, Instant timestamp) {
        return new WallEvent(INSTRUMENT, side, price, size, timestamp, type);
    }
}
