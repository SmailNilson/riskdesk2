package com.riskdesk.application.quant.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.engine.GateEvaluator;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.model.QuantState;
import com.riskdesk.domain.quant.port.AbsorptionPort;
import com.riskdesk.domain.quant.port.CyclePort;
import com.riskdesk.domain.quant.port.DeltaPort;
import com.riskdesk.domain.quant.port.DistributionPort;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.domain.quant.port.QuantNotificationPort;
import com.riskdesk.domain.quant.port.QuantStatePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the transition-based alert contract on
 * {@link QuantGateService#publish}: the one-shot {@code publishShortSignal7_7}
 * / {@code publishSetupAlert6_7} alerts must fire only when the
 * per-instrument score TRANSITIONS into the 6/7 or 7/7 band — never on
 * persistence (PR #297, review #5). Re-firing on every scan would flood the
 * trader with audio pings while a setup sits at 6 or 7 for several minutes,
 * and contradicts the project's "alerts fire on state change, not
 * persistence" rule (CLAUDE.md / ARCHITECTURE_PRINCIPLES.md).
 *
 * <p>The publish method is package-private so we can exercise it with
 * scripted scores without simulating the full data-fetch + evaluator
 * pipeline — the gates are covered by {@code GateEvaluatorTest}; here we
 * only care about the publish-side transition logic.</p>
 */
class QuantGateServiceAlertTransitionTest {

    private RecordingNotificationPort notif;
    private QuantGateService service;

    @BeforeEach
    void setUp() {
        notif = new RecordingNotificationPort();
        service = new QuantGateService(
            (instr, since) -> List.of(),
            (instr, since) -> List.of(),
            (instr, since) -> List.of(),
            instr -> Optional.empty(),
            instr -> Optional.empty(),
            new InMemoryStatePort(), notif,
            new QuantSnapshotHistoryStore(),
            new GateEvaluator()
        );
    }

    @Test
    @DisplayName("Persisting at 7/7 across N scans → publishShortSignal7_7 fires exactly once")
    void persistingAt7_7_firesOnce() {
        for (int i = 0; i < 5; i++) {
            service.publish(Instrument.MNQ, snapshotWithScore(7));
        }
        assertThat(notif.shortSignal7Count.get()).isEqualTo(1);
        assertThat(notif.setup6Count.get()).isZero();
        assertThat(notif.snapshotCount.get())
            .as("snapshot publish runs every tick")
            .isEqualTo(5);
    }

    @Test
    @DisplayName("Persisting at 6/7 across N scans → publishSetupAlert6_7 fires exactly once")
    void persistingAt6_7_firesOnce() {
        for (int i = 0; i < 5; i++) {
            service.publish(Instrument.MNQ, snapshotWithScore(6));
        }
        assertThat(notif.setup6Count.get()).isEqualTo(1);
        assertThat(notif.shortSignal7Count.get()).isZero();
    }

    @Test
    @DisplayName("Setup lost then regained → fresh 7/7 alert on each re-entry")
    void setupLostThenRegained_refires() {
        service.publish(Instrument.MNQ, snapshotWithScore(7));  // fire #1
        service.publish(Instrument.MNQ, snapshotWithScore(7));  // persist
        service.publish(Instrument.MNQ, snapshotWithScore(5));  // reset (below 6)
        service.publish(Instrument.MNQ, snapshotWithScore(7));  // fire #2
        service.publish(Instrument.MNQ, snapshotWithScore(7));  // persist

        assertThat(notif.shortSignal7Count.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Escalation 6→7 → 7/7 fires; 6/7 does NOT re-fire on the way back down")
    void escalation6to7_firesOnly7And_doesNotRefireOn7to6() {
        service.publish(Instrument.MNQ, snapshotWithScore(6));  // fire 6/7
        service.publish(Instrument.MNQ, snapshotWithScore(7));  // fire 7/7 (escalation)
        service.publish(Instrument.MNQ, snapshotWithScore(6));  // does NOT re-fire 6/7
        service.publish(Instrument.MNQ, snapshotWithScore(6));  // still no fire

        assertThat(notif.setup6Count.get()).isEqualTo(1);
        assertThat(notif.shortSignal7Count.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Per-instrument tracking: MNQ alerts do not block MGC alerts")
    void perInstrumentTracking() {
        service.publish(Instrument.MNQ, snapshotWithScore(7));
        service.publish(Instrument.MGC, snapshotWithScore(7));
        service.publish(Instrument.MNQ, snapshotWithScore(7));  // persist on MNQ
        service.publish(Instrument.MGC, snapshotWithScore(7));  // persist on MGC

        assertThat(notif.shortSignal7Count.get())
            .as("each instrument fires its own 7/7 once")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("Score below 6 never fires the one-shot alerts but still publishes the snapshot")
    void scoreBelow6_neverFires() {
        for (int s : new int[]{0, 1, 2, 3, 4, 5}) {
            service.publish(Instrument.MNQ, snapshotWithScore(s));
        }
        assertThat(notif.shortSignal7Count.get()).isZero();
        assertThat(notif.setup6Count.get()).isZero();
        assertThat(notif.snapshotCount.get()).isEqualTo(6);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static QuantSnapshot snapshotWithScore(int score) {
        Map<Gate, GateResult> gates = new EnumMap<>(Gate.class);
        // The gate map content is irrelevant to publish() — only the score
        // and the boolean derivations on QuantSnapshot drive the transition
        // logic. Fill with passing/failing entries that match the score so
        // the snapshot stays self-consistent for any test that inspects it.
        Gate[] all = Gate.values();
        for (int i = 0; i < all.length; i++) {
            gates.put(all[i], i < score ? GateResult.pass("ok") : GateResult.fail("ko"));
        }
        return new QuantSnapshot(
            Instrument.MNQ, gates, score, 20_000.0, "LIVE_PUSH", 0.0,
            ZonedDateTime.now(ZoneId.of("America/New_York"))
        );
    }

    private static final class InMemoryStatePort implements QuantStatePort {
        private final Map<Instrument, QuantState> store = new java.util.HashMap<>();
        @Override public QuantState load(Instrument i) { return store.get(i); }
        @Override public void save(Instrument i, QuantState s) { store.put(i, s); }
    }

    private static final class RecordingNotificationPort implements QuantNotificationPort {
        final AtomicInteger snapshotCount = new AtomicInteger();
        final AtomicInteger shortSignal7Count = new AtomicInteger();
        final AtomicInteger setup6Count = new AtomicInteger();

        @Override public void publishSnapshot(Instrument i, QuantSnapshot s) { snapshotCount.incrementAndGet(); }
        @Override public void publishShortSignal7_7(Instrument i, QuantSnapshot s) { shortSignal7Count.incrementAndGet(); }
        @Override public void publishSetupAlert6_7(Instrument i, QuantSnapshot s) { setup6Count.incrementAndGet(); }
    }

    /** Stays referenced for compile-time so unused imports below stay valid. */
    @SuppressWarnings("unused")
    private static final AbsorptionPort UNUSED_ABS = (i, s) -> List.of();
    @SuppressWarnings("unused")
    private static final DistributionPort UNUSED_DIST = (i, s) -> List.of();
    @SuppressWarnings("unused")
    private static final CyclePort UNUSED_CYC = (i, s) -> List.of();
    @SuppressWarnings("unused")
    private static final DeltaPort UNUSED_DELTA = i -> Optional.empty();
    @SuppressWarnings("unused")
    private static final LivePricePort UNUSED_PX = i -> Optional.empty();
}
