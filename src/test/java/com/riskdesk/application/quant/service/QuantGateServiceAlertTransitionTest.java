package com.riskdesk.application.quant.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.advisor.AiAdvice;
import com.riskdesk.domain.quant.engine.GateEvaluator;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.model.QuantState;
import com.riskdesk.domain.quant.narrative.QuantNarrator;
import com.riskdesk.domain.quant.pattern.OrderFlowPatternDetector;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import com.riskdesk.domain.quant.port.QuantNotificationPort;
import com.riskdesk.domain.quant.port.QuantStatePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

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
 * persistence (PR #297, review #5). The transition state ({@code prev →
 * current}) lives in {@link QuantState#lastSignaledScore()} and is
 * round-tripped through {@code QuantStatePort}, so it survives process
 * restarts (PR #297, review #7).
 */
class QuantGateServiceAlertTransitionTest {

    private RecordingNotificationPort notif;
    private QuantGateService service;
    /** Mirrors what scan() does in production: track prev signaled per instrument across calls. */
    private final Map<Instrument, Integer> testPrev = new EnumMap<>(Instrument.class);

    @BeforeEach
    void setUp() {
        notif = new RecordingNotificationPort();
        testPrev.clear();
        QuantSnapshotHistoryStore history = new QuantSnapshotHistoryStore();
        QuantSetupNarrationService narration = new QuantSetupNarrationService(
            history, new OrderFlowPatternDetector(), new QuantNarrator()
        );
        QuantSessionMemoryService session = new QuantSessionMemoryService();
        QuantAiAdvisorService advisor = new QuantAiAdvisorService(
            EmptyProvider.of(), EmptyProvider.of(), EmptyProvider.of(),
            session, history, 6, 30, 5
        );
        service = new QuantGateService(
            (instr, since) -> List.of(),
            (instr, since) -> List.of(),
            (instr, since) -> List.of(),
            instr -> Optional.empty(),
            instr -> Optional.empty(),
            new InMemoryStatePort(), notif,
            history, narration, session, advisor,
            new GateEvaluator(),
            instr -> Optional.empty(),
            instr -> Optional.empty(),
            new com.riskdesk.domain.quant.structure.StructuralFilterEvaluator()
        );
    }

    /** Mirrors what scan() does: emit + advance the prev tracker for the next call. */
    private void publishWithTracking(Instrument instr, QuantSnapshot snap) {
        int prev = testPrev.getOrDefault(instr, 0);
        service.publish(instr, snap, prev);
        testPrev.put(instr, QuantGateService.nextSignaledScoreFor(prev, snap.score()));
    }

    @Test
    @DisplayName("Persisting at 7/7 across N scans → publishShortSignal7_7 fires exactly once")
    void persistingAt7_7_firesOnce() {
        for (int i = 0; i < 5; i++) {
            publishWithTracking(Instrument.MNQ, snapshotWithScore(7));
        }
        assertThat(notif.shortSignal7Count.get()).isEqualTo(1);
        assertThat(notif.setup6Count.get()).isZero();
        assertThat(notif.snapshotCount.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("Persisting at 6/7 across N scans → publishSetupAlert6_7 fires exactly once")
    void persistingAt6_7_firesOnce() {
        for (int i = 0; i < 5; i++) {
            publishWithTracking(Instrument.MNQ, snapshotWithScore(6));
        }
        assertThat(notif.setup6Count.get()).isEqualTo(1);
        assertThat(notif.shortSignal7Count.get()).isZero();
    }

    @Test
    @DisplayName("Setup lost then regained → fresh 7/7 alert on each re-entry")
    void setupLostThenRegained_refires() {
        publishWithTracking(Instrument.MNQ, snapshotWithScore(7));
        publishWithTracking(Instrument.MNQ, snapshotWithScore(7));
        publishWithTracking(Instrument.MNQ, snapshotWithScore(5));
        publishWithTracking(Instrument.MNQ, snapshotWithScore(7));
        publishWithTracking(Instrument.MNQ, snapshotWithScore(7));
        assertThat(notif.shortSignal7Count.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Escalation 6→7 → 7/7 fires; 6/7 does NOT re-fire on the way back down")
    void escalation6to7_firesOnly7And_doesNotRefireOn7to6() {
        publishWithTracking(Instrument.MNQ, snapshotWithScore(6));
        publishWithTracking(Instrument.MNQ, snapshotWithScore(7));
        publishWithTracking(Instrument.MNQ, snapshotWithScore(6));
        publishWithTracking(Instrument.MNQ, snapshotWithScore(6));
        assertThat(notif.setup6Count.get()).isEqualTo(1);
        assertThat(notif.shortSignal7Count.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Per-instrument tracking: MNQ alerts do not block MGC alerts")
    void perInstrumentTracking() {
        publishWithTracking(Instrument.MNQ, snapshotWithScore(7));
        publishWithTracking(Instrument.MGC, snapshotWithScore(7));
        publishWithTracking(Instrument.MNQ, snapshotWithScore(7));
        publishWithTracking(Instrument.MGC, snapshotWithScore(7));
        assertThat(notif.shortSignal7Count.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Score below 6 never fires the one-shot alerts but still publishes the snapshot")
    void scoreBelow6_neverFires() {
        for (int s : new int[]{0, 1, 2, 3, 4, 5}) {
            publishWithTracking(Instrument.MNQ, snapshotWithScore(s));
        }
        assertThat(notif.shortSignal7Count.get()).isZero();
        assertThat(notif.setup6Count.get()).isZero();
        assertThat(notif.snapshotCount.get()).isEqualTo(6);
    }

    @Test
    @DisplayName("nextSignaledScoreFor: pure transition table")
    void transitionTable() {
        // 0→7 fires; 0→6 fires
        assertThat(QuantGateService.nextSignaledScoreFor(0, 7)).isEqualTo(7);
        assertThat(QuantGateService.nextSignaledScoreFor(0, 6)).isEqualTo(6);
        // Persistence: stays
        assertThat(QuantGateService.nextSignaledScoreFor(7, 7)).isEqualTo(7);
        assertThat(QuantGateService.nextSignaledScoreFor(6, 6)).isEqualTo(6);
        // 7→6 keeps prev (no re-fire on the way down)
        assertThat(QuantGateService.nextSignaledScoreFor(7, 6)).isEqualTo(7);
        // Drop below 6 resets so the next rise re-fires
        assertThat(QuantGateService.nextSignaledScoreFor(7, 5)).isZero();
        assertThat(QuantGateService.nextSignaledScoreFor(7, 4)).isZero();
        assertThat(QuantGateService.nextSignaledScoreFor(6, 0)).isZero();
        // 6→7 escalation
        assertThat(QuantGateService.nextSignaledScoreFor(6, 7)).isEqualTo(7);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static QuantSnapshot snapshotWithScore(int score) {
        Map<Gate, GateResult> gates = new EnumMap<>(Gate.class);
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
        @Override public void publishNarration(Instrument i, QuantSnapshot s, PatternAnalysis p, String md) {}
        @Override public void publishAdvice(Instrument i, QuantSnapshot s, AiAdvice a) {}
    }

    private static final class EmptyProvider<T> implements ObjectProvider<T> {
        static <T> EmptyProvider<T> of() { return new EmptyProvider<>(); }
        @Override public T getObject(Object... args) { return null; }
        @Override public T getObject() { return null; }
        @Override public T getIfAvailable() { return null; }
        @Override public T getIfUnique() { return null; }
    }
}
