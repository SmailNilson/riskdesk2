package com.riskdesk.application.quant.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.advisor.AiAdvice;
import com.riskdesk.domain.quant.engine.GateEvaluator;
import com.riskdesk.domain.quant.model.DeltaSnapshot;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.model.QuantState;
import com.riskdesk.domain.quant.narrative.QuantNarrator;
import com.riskdesk.domain.quant.pattern.OrderFlowPatternDetector;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import com.riskdesk.domain.quant.port.AbsorptionPort;
import com.riskdesk.domain.quant.port.CyclePort;
import com.riskdesk.domain.quant.port.DeltaPort;
import com.riskdesk.domain.quant.port.DistributionPort;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.domain.quant.port.QuantNotificationPort;
import com.riskdesk.domain.quant.port.QuantStatePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the cross-restart durability of the publisher's transition tracker
 * (PR #297, review #7 P2): {@code lastSignaledScore} now lives in
 * {@link QuantState} and is persisted via {@code QuantStatePort}, so a
 * process restart while a setup is sitting at 7/7 must NOT re-fire the
 * audio ping on every connected dashboard.
 */
class QuantGateServiceRestartPersistenceTest {

    @Test
    @DisplayName("After restart, a persisted lastSignaledScore=7 prevents re-firing the 7/7 alert")
    void lastSignaledScoreSurvivesProcessRestart() {
        SharedStatePort statePort = new SharedStatePort();

        // ── Process #1: state seeded with lastSignaledScore=7 (alert was fired
        // before the restart). The current scan will land on a 7/7 setup, but
        // because the persisted prev is already 7, no fresh alert must fire.
        statePort.store.put(Instrument.MNQ, QuantState.reset(LocalDate.now())
            .withMonitorStartPx(20_000.0)
            .withLastSignaledScore(7));

        RecordingNotificationPort notif1 = new RecordingNotificationPort();
        QuantGateService svc1 = newService(statePort, notif1);

        // First publish in this process — should NOT fire because prev (loaded
        // from state) was 7 and current score stays at 7.
        QuantState saved = statePort.load(Instrument.MNQ);
        int prev = saved.lastSignaledScore();
        QuantSnapshot snap = snapshotWithScore(7);
        svc1.publish(Instrument.MNQ, snap, prev);

        assertThat(notif1.shortSignal7Count.get())
            .as("setup at 7/7 already alerted before restart — must NOT re-fire")
            .isZero();

        // ── Sanity check the inverse: if the persisted state had reset (e.g.
        // because the score had dropped below 6 before restart), the alert
        // SHOULD fire fresh on the next 7/7.
        statePort.store.put(Instrument.MNQ, saved.withLastSignaledScore(0));
        RecordingNotificationPort notif2 = new RecordingNotificationPort();
        QuantGateService svc2 = newService(statePort, notif2);

        prev = statePort.load(Instrument.MNQ).lastSignaledScore();
        svc2.publish(Instrument.MNQ, snap, prev);

        assertThat(notif2.shortSignal7Count.get())
            .as("clean prev state means a fresh 7/7 fires correctly")
            .isEqualTo(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static QuantGateService newService(QuantStatePort statePort,
                                                QuantNotificationPort notif) {
        QuantSnapshotHistoryStore history = new QuantSnapshotHistoryStore();
        QuantSetupNarrationService narration = new QuantSetupNarrationService(
            history, new OrderFlowPatternDetector(), new QuantNarrator()
        );
        QuantSessionMemoryService session = new QuantSessionMemoryService();
        QuantAiAdvisorService advisor = new QuantAiAdvisorService(
            EmptyProvider.of(), EmptyProvider.of(), EmptyProvider.of(),
            session, history, 6, 30, 5
        );
        return new QuantGateService(
            (instr, since) -> List.of(),
            (instr, since) -> List.of(),
            (instr, since) -> List.of(),
            instr -> Optional.of(new DeltaSnapshot(-150.0, 45.0, Instant.now(), "REAL_TICKS")),
            instr -> Optional.of(new LivePriceSnapshot(20_000.0, Instant.now(), "LIVE_PUSH")),
            statePort, notif,
            history, narration, session, advisor,
            new GateEvaluator(),
            instr -> Optional.empty(),
            instr -> Optional.empty(),
            new com.riskdesk.domain.quant.structure.StructuralFilterEvaluator()
        );
    }

    private static QuantSnapshot snapshotWithScore(int score) {
        Map<com.riskdesk.domain.quant.model.Gate, com.riskdesk.domain.quant.model.GateResult> gates = new java.util.EnumMap<>(com.riskdesk.domain.quant.model.Gate.class);
        com.riskdesk.domain.quant.model.Gate[] all = com.riskdesk.domain.quant.model.Gate.values();
        for (int i = 0; i < all.length; i++) {
            gates.put(all[i], i < score
                ? com.riskdesk.domain.quant.model.GateResult.pass("ok")
                : com.riskdesk.domain.quant.model.GateResult.fail("ko"));
        }
        return new QuantSnapshot(
            Instrument.MNQ, gates, score, 20_000.0, "LIVE_PUSH", 0.0,
            java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"))
        );
    }

    /** State port shared across the two simulated process instances. */
    private static final class SharedStatePort implements QuantStatePort {
        final Map<Instrument, QuantState> store = new HashMap<>();
        @Override public QuantState load(Instrument i) { return store.get(i); }
        @Override public void save(Instrument i, QuantState s) { store.put(i, s); }
    }

    private static final class RecordingNotificationPort implements QuantNotificationPort {
        final AtomicInteger shortSignal7Count = new AtomicInteger();
        @Override public void publishSnapshot(Instrument i, QuantSnapshot s) {}
        @Override public void publishShortSignal7_7(Instrument i, QuantSnapshot s) { shortSignal7Count.incrementAndGet(); }
        @Override public void publishSetupAlert6_7(Instrument i, QuantSnapshot s) {}
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

    /** Compile-time refs to keep imports referenced from the helper. */
    @SuppressWarnings("unused") private static final AbsorptionPort UNUSED_ABS = (i, s) -> List.of();
    @SuppressWarnings("unused") private static final DistributionPort UNUSED_DIST = (i, s) -> List.of();
    @SuppressWarnings("unused") private static final CyclePort UNUSED_CYC = (i, s) -> List.of();
    @SuppressWarnings("unused") private static final DeltaPort UNUSED_DELTA = i -> Optional.empty();
    @SuppressWarnings("unused") private static final LivePricePort UNUSED_PX = i -> Optional.empty();
}
