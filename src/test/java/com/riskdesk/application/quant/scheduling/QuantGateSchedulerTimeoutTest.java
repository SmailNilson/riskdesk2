package com.riskdesk.application.quant.scheduling;

import com.riskdesk.application.quant.service.QuantAiAdvisorService;
import com.riskdesk.application.quant.service.QuantGateService;
import com.riskdesk.application.quant.service.QuantSessionMemoryService;
import com.riskdesk.application.quant.service.QuantSetupNarrationService;
import com.riskdesk.application.quant.service.QuantSnapshotHistoryStore;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.advisor.AiAdvice;
import com.riskdesk.domain.quant.engine.GateEvaluator;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.model.QuantState;
import com.riskdesk.domain.quant.narrative.QuantNarrator;
import com.riskdesk.domain.quant.pattern.OrderFlowPatternDetector;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import com.riskdesk.domain.quant.port.QuantNotificationPort;
import com.riskdesk.domain.quant.port.QuantStatePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the scheduler-freeze guard added in PR #297 review #7 P1: a single
 * hung {@code service.scan(instr)} must not stop the {@code @Scheduled}
 * loop from firing for the other instruments. The scan is bounded by
 * {@code riskdesk.quant.scan-timeout-ms} via
 * {@link java.util.concurrent.CompletableFuture#orTimeout}; recovered
 * exceptions are logged and the next tick fires on schedule.
 */
class QuantGateSchedulerTimeoutTest {

    @Test
    @DisplayName("A scan that hangs longer than the timeout does not freeze the scheduler")
    void scanAllReturnsEvenWhenOneInstrumentHangs() {
        AtomicInteger scanCount = new AtomicInteger();
        QuantGateService blockingService = newBlockingService(scanCount, /*hangMs=*/ 5_000);

        // Tight timeout to force the scenario without slowing the test suite.
        QuantGateScheduler scheduler = new QuantGateScheduler(blockingService, /*scanTimeoutMs=*/ 100);

        long before = System.currentTimeMillis();
        scheduler.scanAll();
        long elapsed = System.currentTimeMillis() - before;

        // The scheduler must return well before the per-scan hang completes.
        // 3 instruments × 100ms timeout, with a generous slack for CI noise.
        assertThat(elapsed)
            .as("scanAll must not block on hung scans (returned in %dms, timeout=100ms × 3)", elapsed)
            .isLessThan(2_000);

        // All three scans were attempted (none was skipped).
        assertThat(scanCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("A scan that throws does not abort the rest of the loop")
    void scanAllRecoversFromExceptions() {
        AtomicInteger ok = new AtomicInteger();
        QuantGateService throwingService = new QuantGateService(
            (i, s) -> List.of(), (i, s) -> List.of(), (i, s) -> List.of(),
            i -> Optional.empty(), i -> Optional.empty(),
            new InMemoryStatePort(), new SilentNotificationPort(),
            new QuantSnapshotHistoryStore(),
            new QuantSetupNarrationService(new QuantSnapshotHistoryStore(),
                new OrderFlowPatternDetector(), new QuantNarrator()),
            new QuantSessionMemoryService(),
            advisor(),
            new GateEvaluator(),
            i -> Optional.empty(),
            i -> Optional.empty(),
            new com.riskdesk.domain.quant.structure.StructuralFilterEvaluator()
        ) {
            @Override
            public QuantSnapshot scan(Instrument instrument) {
                ok.incrementAndGet();
                if (instrument == Instrument.MGC) {
                    throw new IllegalStateException("simulated MGC port outage");
                }
                return null;
            }
        };

        QuantGateScheduler scheduler = new QuantGateScheduler(throwingService, /*scanTimeoutMs=*/ 5_000);
        scheduler.scanAll();  // must not throw

        assertThat(ok.get())
            .as("MNQ + MGC + MCL all attempted even though MGC threw")
            .isEqualTo(3);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static QuantGateService newBlockingService(AtomicInteger scanCount, long hangMs) {
        QuantSnapshotHistoryStore history = new QuantSnapshotHistoryStore();
        return new QuantGateService(
            (i, s) -> List.of(), (i, s) -> List.of(), (i, s) -> List.of(),
            i -> Optional.empty(), i -> Optional.empty(),
            new InMemoryStatePort(), new SilentNotificationPort(),
            history,
            new QuantSetupNarrationService(history, new OrderFlowPatternDetector(), new QuantNarrator()),
            new QuantSessionMemoryService(),
            advisor(),
            new GateEvaluator(),
            i -> Optional.empty(),
            i -> Optional.empty(),
            new com.riskdesk.domain.quant.structure.StructuralFilterEvaluator()
        ) {
            @Override
            public QuantSnapshot scan(Instrument instrument) {
                scanCount.incrementAndGet();
                try { Thread.sleep(hangMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return null;
            }
        };
    }

    private static QuantAiAdvisorService advisor() {
        return new QuantAiAdvisorService(
            EmptyProvider.of(), EmptyProvider.of(), EmptyProvider.of(),
            new QuantSessionMemoryService(), new QuantSnapshotHistoryStore(),
            6, 30, 5
        );
    }

    private static final class InMemoryStatePort implements QuantStatePort {
        private final Map<Instrument, QuantState> store = new HashMap<>();
        @Override public QuantState load(Instrument i) { return store.get(i); }
        @Override public void save(Instrument i, QuantState s) { store.put(i, s); }
    }

    private static final class SilentNotificationPort implements QuantNotificationPort {
        @Override public void publishSnapshot(Instrument i, QuantSnapshot s) {}
        @Override public void publishShortSignal7_7(Instrument i, QuantSnapshot s) {}
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

    /** Keeps the Duration import grounded for static analyzers. */
    @SuppressWarnings("unused")
    private static final Duration UNUSED = Duration.ZERO;
}
