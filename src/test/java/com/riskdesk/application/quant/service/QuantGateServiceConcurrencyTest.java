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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three concurrency contracts on {@link QuantGateService#scan} are pinned here:
 *
 * <ol>
 *   <li><b>State integrity</b> — N concurrent scans on the same instrument
 *       must produce N sequential saves; no two threads may sit inside the
 *       {@code load → evaluate → save} window at the same time (PR #297, review #1).</li>
 *   <li><b>Publish ordering matches state ordering</b> — the publish step
 *       must happen inside the same lock as the state mutation (review #2).</li>
 *   <li><b>Capture ordering matches publish ordering</b> — the input fetches
 *       must happen inside the lock too. If a slow scan captures inputs at
 *       T1 and waits for the lock while a faster scan captures at T2 &gt; T1
 *       and publishes first, the slow scan's older snapshot would land last
 *       (review #3).</li>
 * </ol>
 */
class QuantGateServiceConcurrencyTest {

    @Test
    @DisplayName("Concurrent scans on the same instrument serialise the load→evaluate→save→publish window")
    void concurrentScansSerialiseStateUpdates() throws Exception {
        RecordingStatePort statePort = new RecordingStatePort();
        RecordingNotificationPort notif = new RecordingNotificationPort();
        QuantGateService service = buildService(statePort, notif);

        int concurrentScans = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrentScans);

        for (int i = 0; i < concurrentScans; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.scan(Instrument.MNQ);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).as("all 16 scans completed").isTrue();
        assertThat(statePort.saveCount.get()).isEqualTo(concurrentScans);
        assertThat(statePort.loadCount.get()).isEqualTo(concurrentScans);
        assertThat(statePort.peakConcurrentInState.get())
            .as("at most one scan was inside the load→save→publish window at a time")
            .isEqualTo(1);
        assertThat(notif.peakConcurrentPublishes.get())
            .as("publish must not run concurrently with another scan's state mutation")
            .isEqualTo(1);
        assertThat(notif.snapshotPublishes.get()).isEqualTo(concurrentScans);
    }

    @Test
    @DisplayName("latestSnapshot returns null on cold start, then the most recent published snapshot")
    void latestSnapshotIsReadOnly() {
        RecordingStatePort statePort = new RecordingStatePort();
        RecordingNotificationPort notif = new RecordingNotificationPort();
        QuantGateService service = buildService(statePort, notif);

        // Cold start: no scan has run yet.
        assertThat(service.latestSnapshot(Instrument.MNQ)).isNull();
        assertThat(statePort.loadCount.get()).isZero();
        assertThat(notif.snapshotPublishes.get()).isZero();

        service.scan(Instrument.MNQ);
        int loadAfterScan = statePort.loadCount.get();
        int publishesAfterScan = notif.snapshotPublishes.get();

        assertThat(service.latestSnapshot(Instrument.MNQ)).isNotNull();
        assertThat(service.latestSnapshot(Instrument.MNQ)).isNotNull();
        assertThat(statePort.loadCount.get()).isEqualTo(loadAfterScan);
        assertThat(notif.snapshotPublishes.get()).isEqualTo(publishesAfterScan);
    }

    @Test
    @DisplayName("latestSnapshot ignores age — survives a scheduler stall longer than the dashboard window")
    void latestSnapshotIgnoresAge() {
        RecordingStatePort statePort = new RecordingStatePort();
        RecordingNotificationPort notif = new RecordingNotificationPort();
        QuantGateService service = buildService(statePort, notif);

        service.scan(Instrument.MNQ);
        QuantSnapshot fresh = service.latestSnapshot(Instrument.MNQ);
        assertThat(fresh).isNotNull();
        // Repeated reads remain stable and never trigger a scan.
        assertThat(service.latestSnapshot(Instrument.MNQ)).isSameAs(fresh);
    }

    @Test
    @DisplayName("Capture-time order matches publish-time order under contention")
    void publishOrderMatchesCaptureOrder() throws Exception {
        RecordingStatePort statePort = new RecordingStatePort();
        OrderedPublishRecorder publishOrder = new OrderedPublishRecorder();
        java.util.concurrent.atomic.AtomicInteger arrivalOrder = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Long> captureTimes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        DeltaPort delta = instr -> {
            int order = arrivalOrder.incrementAndGet();
            // The first scan to enter is slowed to expose the race the lock prevents.
            if (order == 1) {
                try { Thread.sleep(80); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            captureTimes.add(System.nanoTime());
            return Optional.of(new DeltaSnapshot(-150.0, 45.0, Instant.now(), "REAL_TICKS"));
        };

        QuantSnapshotHistoryStore history = new QuantSnapshotHistoryStore();
        QuantSetupNarrationService narration = new QuantSetupNarrationService(
            history, new OrderFlowPatternDetector(), new QuantNarrator()
        );
        QuantSessionMemoryService session = new QuantSessionMemoryService();
        QuantAiAdvisorService advisor = new QuantAiAdvisorService(
            EmptyProvider.of(), EmptyProvider.of(), EmptyProvider.of(),
            session, history, 6, 30, 5
        );
        QuantGateService service = new QuantGateService(
            (instr, since) -> List.of(),
            (instr, since) -> List.of(),
            (instr, since) -> List.of(),
            delta,
            instr -> Optional.of(new LivePriceSnapshot(20_000.0, Instant.now(), "LIVE_PUSH")),
            statePort, publishOrder,
            history, narration, session, advisor,
            new GateEvaluator()
        );

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try { start.await(); service.scan(Instrument.MNQ); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { done.countDown(); }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(publishOrder.publishTimes).hasSize(2);
        assertThat(publishOrder.publishTimes.get(0)).isLessThan(publishOrder.publishTimes.get(1));
        assertThat(captureTimes).hasSize(2);
        assertThat(captureTimes.get(0)).isLessThan(captureTimes.get(1));
    }

    private static QuantGateService buildService(RecordingStatePort statePort,
                                                  RecordingNotificationPort notif) {
        AbsorptionPort absorption       = (instr, since) -> List.of();
        DistributionPort distribution   = (instr, since) -> List.of();
        CyclePort cycle                  = (instr, since) -> List.of();
        DeltaPort delta                  = instr -> Optional.of(new DeltaSnapshot(-150.0, 45.0, Instant.now(), "REAL_TICKS"));
        LivePricePort livePrice          = instr -> Optional.of(new LivePriceSnapshot(20_000.0, Instant.now(), "LIVE_PUSH"));

        QuantSnapshotHistoryStore history = new QuantSnapshotHistoryStore();
        QuantSetupNarrationService narration = new QuantSetupNarrationService(
            history,
            new OrderFlowPatternDetector(),
            new QuantNarrator()
        );
        QuantSessionMemoryService session = new QuantSessionMemoryService();
        QuantAiAdvisorService advisor = new QuantAiAdvisorService(
            EmptyProvider.of(), EmptyProvider.of(), EmptyProvider.of(),
            session, history, 6, 30, 5
        );

        return new QuantGateService(
            absorption, distribution, cycle, delta, livePrice,
            statePort, notif,
            history, narration, session, advisor,
            new GateEvaluator()
        );
    }

    /** Counts loads / saves and tracks the peak number of scans simultaneously inside the critical section. */
    private static final class RecordingStatePort implements QuantStatePort {
        final java.util.concurrent.atomic.AtomicInteger loadCount = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger saveCount = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger peakConcurrentInState = new java.util.concurrent.atomic.AtomicInteger();
        private final Map<Instrument, QuantState> store = new ConcurrentHashMap<>();

        @Override
        public QuantState load(Instrument instrument) {
            int now = inFlight.incrementAndGet();
            peakConcurrentInState.accumulateAndGet(now, Math::max);
            loadCount.incrementAndGet();
            try { Thread.sleep(2); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return store.get(instrument);
        }

        @Override
        public void save(Instrument instrument, QuantState state) {
            store.put(instrument, state);
            saveCount.incrementAndGet();
            inFlight.decrementAndGet();
        }
    }

    /** Counts publishes and tracks the peak concurrent publishes. */
    private static final class RecordingNotificationPort implements QuantNotificationPort {
        final java.util.concurrent.atomic.AtomicInteger snapshotPublishes = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger peakConcurrentPublishes = new java.util.concurrent.atomic.AtomicInteger();

        @Override public void publishSnapshot(Instrument i, QuantSnapshot s) {
            int now = inFlight.incrementAndGet();
            peakConcurrentPublishes.accumulateAndGet(now, Math::max);
            try { Thread.sleep(1); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            snapshotPublishes.incrementAndGet();
            inFlight.decrementAndGet();
        }
        @Override public void publishShortSignal7_7(Instrument i, QuantSnapshot s) {}
        @Override public void publishSetupAlert6_7(Instrument i, QuantSnapshot s) {}
        @Override public void publishNarration(Instrument i, QuantSnapshot s, PatternAnalysis p, String md) {}
        @Override public void publishAdvice(Instrument i, QuantSnapshot s, AiAdvice a) {}
    }

    /** Records publish entry times in arrival order. */
    private static final class OrderedPublishRecorder implements QuantNotificationPort {
        final java.util.List<Long> publishTimes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @Override public void publishSnapshot(Instrument i, QuantSnapshot s) {
            publishTimes.add(System.nanoTime());
        }
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
}
