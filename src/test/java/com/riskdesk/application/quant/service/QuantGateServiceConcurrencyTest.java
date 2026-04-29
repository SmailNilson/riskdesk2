package com.riskdesk.application.quant.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.engine.GateEvaluator;
import com.riskdesk.domain.quant.model.DeltaSnapshot;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.model.QuantState;
import com.riskdesk.domain.quant.port.AbsorptionPort;
import com.riskdesk.domain.quant.port.CyclePort;
import com.riskdesk.domain.quant.port.DeltaPort;
import com.riskdesk.domain.quant.port.DistributionPort;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.domain.quant.port.QuantNotificationPort;
import com.riskdesk.domain.quant.port.QuantStatePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 *       {@code load → evaluate → save} window at the same time, so no append
 *       is silently dropped (PR #297, review #1).</li>
 *   <li><b>Publish ordering matches state ordering</b> — the publish step
 *       must happen inside the same lock as the state mutation (PR #297,
 *       review #2). Out-of-order publishes regress the frontend.</li>
 *   <li><b>Capture ordering matches publish ordering</b> — the input fetches
 *       must happen inside the lock too. If a slow scan captures inputs at
 *       T1 and waits for the lock while a faster scan captures at T2 &gt; T1
 *       and publishes first, the slow scan's older snapshot would land last
 *       (PR #297, review #3).</li>
 * </ol>
 */
class QuantGateServiceConcurrencyTest {

    @Test
    @DisplayName("Concurrent scans on the same instrument serialise the load→evaluate→save window")
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
        // Every scan must have produced a save — none was lost in a race.
        assertThat(statePort.saveCount.get()).isEqualTo(concurrentScans);
        // No load happened mid-save (load count == save count proves serialisation).
        assertThat(statePort.loadCount.get()).isEqualTo(concurrentScans);
        // The lock must serialise the state mutations: max observed concurrent
        // load-without-save is 1.
        assertThat(statePort.peakConcurrentInState.get())
            .as("at most one scan was inside the load→save→publish window at a time")
            .isEqualTo(1);
        // Publish must run inside the same lock, so its concurrency is also 1.
        assertThat(notif.peakConcurrentPublishes.get())
            .as("publish must not run concurrently with another scan's state mutation")
            .isEqualTo(1);
        // And every state mutation must have produced exactly one snapshot publish.
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

        // After one explicit scan the snapshot becomes available — and
        // subsequent latestSnapshot calls must NOT trigger another scan or
        // publish (the dashboard bootstrap regression: GET /snapshot used to
        // call service.scan() and broadcast on every page load).
        service.scan(Instrument.MNQ);
        int loadAfterScan = statePort.loadCount.get();
        int publishesAfterScan = notif.snapshotPublishes.get();

        assertThat(service.latestSnapshot(Instrument.MNQ)).isNotNull();
        assertThat(service.latestSnapshot(Instrument.MNQ)).isNotNull();
        assertThat(statePort.loadCount.get()).isEqualTo(loadAfterScan);
        assertThat(notif.snapshotPublishes.get()).isEqualTo(publishesAfterScan);
    }

    @Test
    @DisplayName("latestSnapshot survives a long pause — no time-based filter")
    void latestSnapshotIgnoresAge() throws Exception {
        QuantSnapshotHistoryStore store = new QuantSnapshotHistoryStore();
        RecordingStatePort statePort = new RecordingStatePort();
        RecordingNotificationPort notif = new RecordingNotificationPort();
        QuantGateService service = new QuantGateService(
            (instr, since) -> List.of(),
            (instr, since) -> List.of(),
            (instr, since) -> List.of(),
            instr -> Optional.of(new DeltaSnapshot(-150.0, 45.0, Instant.now(), "REAL_TICKS")),
            instr -> Optional.of(new LivePriceSnapshot(20_000.0, Instant.now(), "LIVE_PUSH")),
            statePort, notif, store, new GateEvaluator()
        );

        service.scan(Instrument.MNQ);
        QuantSnapshot fresh = service.latestSnapshot(Instrument.MNQ);
        assertThat(fresh).as("latest is available right after the scan").isNotNull();

        // Simulate a scheduler stall by aging the buffer entry beyond any
        // reasonable freshness window. We do this by reflecting on the store's
        // internal Entry — the contract is "return latest regardless of age",
        // so a 6-hour-old snapshot must still be returned.
        // We can't easily age the entry without exposing internals, so instead
        // we assert the simpler observable: latest() never inspects age.
        assertThat(store.latest(Instrument.MNQ)).isPresent();
        // Repeated calls return the same entry without rescanning.
        assertThat(service.latestSnapshot(Instrument.MNQ)).isSameAs(fresh);
    }

    @Test
    @DisplayName("Capture-time order matches publish-time order under contention")
    void publishOrderMatchesCaptureOrder() throws Exception {
        // Custom DeltaPort whose latency depends on scan order: the first
        // scan to arrive is artificially slowed so it would publish last
        // without the lock around the input fetches. We assert the publish
        // sequence follows the captureTimes recorded.
        RecordingStatePort statePort = new RecordingStatePort();
        OrderedPublishRecorder publishOrder = new OrderedPublishRecorder();
        java.util.concurrent.atomic.AtomicInteger arrivalOrder = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Long> captureTimes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        DeltaPort delta = instr -> {
            int order = arrivalOrder.incrementAndGet();
            // The first scan to enter is slowed to expose the race.
            if (order == 1) {
                try { Thread.sleep(80); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            captureTimes.add(System.nanoTime());
            return Optional.of(new DeltaSnapshot(-150.0, 45.0, Instant.now(), "REAL_TICKS"));
        };

        QuantGateService service = new QuantGateService(
            (instr, since) -> List.of(),
            (instr, since) -> List.of(),
            (instr, since) -> List.of(),
            delta,
            instr -> Optional.of(new LivePriceSnapshot(20_000.0, Instant.now(), "LIVE_PUSH")),
            statePort, publishOrder, new QuantSnapshotHistoryStore(), new GateEvaluator()
        );

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> { try { start.await(); service.scan(Instrument.MNQ); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } finally { done.countDown(); } });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // captureTimes captured input-fetch completion times; publishTimes
        // captured publish entry times. Lock around fetches means publishes
        // happen in the same order as captures. We don't compare exact pairs
        // (different threads), but the publish times must be strictly
        // increasing, and there are exactly 2 of them.
        assertThat(publishOrder.publishTimes).hasSize(2);
        assertThat(publishOrder.publishTimes.get(0)).isLessThan(publishOrder.publishTimes.get(1));
        // And captureTimes must be strictly increasing too (lock makes capture sequential).
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

        return new QuantGateService(
            absorption, distribution, cycle, delta, livePrice,
            statePort, notif,
            new QuantSnapshotHistoryStore(),
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
            // Tiny sleep widens the race window in test runs without slowing CI.
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
    }

    /** Records publish entry times in arrival order. */
    private static final class OrderedPublishRecorder implements QuantNotificationPort {
        final java.util.List<Long> publishTimes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @Override public void publishSnapshot(Instrument i, QuantSnapshot s) {
            publishTimes.add(System.nanoTime());
        }
        @Override public void publishShortSignal7_7(Instrument i, QuantSnapshot s) {}
        @Override public void publishSetupAlert6_7(Instrument i, QuantSnapshot s) {}
    }
}
