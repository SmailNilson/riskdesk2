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
 * Reproduces the race the Codex reviewer flagged on PR #297: when the
 * scheduler tick and a manual snapshot request fire in parallel for the same
 * instrument, both reads see the same prior state, both append a delta, and
 * the later save silently drops the earlier append.
 *
 * <p>The lock added in {@code QuantGateService.scan} must serialise the
 * read-evaluate-write window so that, after N concurrent scans on the same
 * instrument, the store has been written N times sequentially and every
 * append is preserved (verified via {@code QuantStatePort.save} call count).</p>
 */
class QuantGateServiceConcurrencyTest {

    @Test
    @DisplayName("Concurrent scans on the same instrument serialise the load→evaluate→save window")
    void concurrentScansSerialiseStateUpdates() throws Exception {
        RecordingStatePort statePort = new RecordingStatePort();
        QuantGateService service = buildService(statePort);

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
            .as("at most one scan was inside the load→save window at a time")
            .isEqualTo(1);
    }

    private static QuantGateService buildService(RecordingStatePort statePort) {
        AbsorptionPort absorption       = (instr, since) -> List.of();
        DistributionPort distribution   = (instr, since) -> List.of();
        CyclePort cycle                  = (instr, since) -> List.of();
        DeltaPort delta                  = instr -> Optional.of(new DeltaSnapshot(-150.0, 45.0, Instant.now(), "REAL_TICKS"));
        LivePricePort livePrice          = instr -> Optional.of(new LivePriceSnapshot(20_000.0, Instant.now(), "LIVE_PUSH"));
        QuantNotificationPort silentNotif = new SilentNotificationPort();

        return new QuantGateService(
            absorption, distribution, cycle, delta, livePrice,
            statePort, silentNotif,
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

    /** No-op notification port used by the concurrency test (we only care about state ordering). */
    private static final class SilentNotificationPort implements QuantNotificationPort {
        @Override public void publishSnapshot(Instrument i, QuantSnapshot s) {}
        @Override public void publishShortSignal7_7(Instrument i, QuantSnapshot s) {}
        @Override public void publishSetupAlert6_7(Instrument i, QuantSnapshot s) {}
    }
}
