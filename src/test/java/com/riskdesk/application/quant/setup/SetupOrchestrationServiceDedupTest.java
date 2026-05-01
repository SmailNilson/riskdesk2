package com.riskdesk.application.quant.setup;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.setup.GateCheckResult;
import com.riskdesk.domain.quant.setup.SetupGateChain;
import com.riskdesk.domain.quant.setup.SetupPhase;
import com.riskdesk.domain.quant.setup.SetupRecommendation;
import com.riskdesk.domain.quant.setup.SetupStyle;
import com.riskdesk.domain.quant.setup.SetupTemplate;
import com.riskdesk.domain.quant.setup.port.RegimeSwitchPolicy;
import com.riskdesk.domain.quant.setup.port.SetupNotificationPort;
import com.riskdesk.domain.quant.setup.port.SetupRepositoryPort;
import com.riskdesk.domain.quant.structure.IndicatorsPort;
import com.riskdesk.domain.quant.structure.StrategyPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link SetupOrchestrationService} does NOT spawn a new
 * {@code DETECTED} row on every qualifying scan and that it invalidates
 * stale ones, addressing the unbounded-growth concern.
 */
class SetupOrchestrationServiceDedupTest {

    private FakeRepo repo;
    private RecordingNotifier notifier;
    private SetupOrchestrationService service;

    @BeforeEach
    void setup() {
        repo = new FakeRepo();
        notifier = new RecordingNotifier();
        SetupGateChain alwaysPass = new SetupGateChain(List.of(
            ctx -> GateCheckResult.pass("DUMMY", "ok")
        ));
        RegimeSwitchPolicy policy = (regime, bb, dm) -> SetupStyle.DAY;
        IndicatorsPort indicators = inst -> Optional.empty();
        StrategyPort strategy = inst -> Optional.empty();

        service = new SetupOrchestrationService(
            alwaysPass, policy, repo, notifier, indicators, strategy
        );
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    private QuantSnapshot snapshotShortSetup() {
        // SHORT score 6, LONG score 0 — direction resolution picks SHORT
        return new QuantSnapshot(
            Instrument.MCL,
            Map.<Gate, GateResult>of(),
            6, 0,
            20_000.0, "TEST", 5.0,
            ZonedDateTime.of(2026, 4, 29, 10, 0, 0, 0, ZoneId.of("America/New_York"))
        );
    }

    @Test
    @DisplayName("repeated scans within TTL produce ONE DETECTED row, not many")
    void dedup_within_ttl() {
        for (int i = 0; i < 5; i++) {
            service.onSnapshot(Instrument.MCL, snapshotShortSetup());
        }
        long shortDetected = repo.store.values().stream()
            .filter(s -> s.direction() == Direction.SHORT)
            .filter(s -> s.phase() == SetupPhase.DETECTED)
            .count();
        assertThat(shortDetected).isEqualTo(1);
        assertThat(notifier.published).hasSize(1);
    }

    @Test
    @DisplayName("concurrent scans never produce duplicate DETECTED rows for same direction")
    void concurrent_scans_no_duplicates() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go    = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    service.onSnapshot(Instrument.MCL, snapshotShortSetup());
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        go.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        long shortDetected = repo.store.values().stream()
            .filter(s -> s.direction() == Direction.SHORT)
            .filter(s -> s.phase() == SetupPhase.DETECTED)
            .count();
        assertThat(shortDetected).isEqualTo(1);
        assertThat(notifier.published).hasSize(1);
    }

    @Test
    @DisplayName("stale DETECTED beyond TTL is invalidated and a fresh one is born")
    void stale_detected_invalidated() {
        // Seed a stale DETECTED row directly (older than TTL)
        UUID staleId = UUID.randomUUID();
        Instant longAgo = Instant.now().minus(Duration.ofHours(2));
        SetupRecommendation stale = new SetupRecommendation(
            staleId, Instrument.MCL, SetupTemplate.D_MTF_ALIGN, SetupStyle.DAY,
            SetupPhase.DETECTED, MarketRegime.UNKNOWN, Direction.SHORT,
            6.0, BigDecimal.valueOf(20_000), BigDecimal.valueOf(20_025),
            BigDecimal.valueOf(19_960), BigDecimal.valueOf(19_920),
            2.0, null, List.of(), longAgo, longAgo
        );
        repo.store.put(staleId, stale);

        service.onSnapshot(Instrument.MCL, snapshotShortSetup());

        SetupRecommendation invalidated = repo.store.get(staleId);
        assertThat(invalidated.phase()).isEqualTo(SetupPhase.INVALIDATED);

        long freshDetected = repo.store.values().stream()
            .filter(s -> s.direction() == Direction.SHORT)
            .filter(s -> s.phase() == SetupPhase.DETECTED)
            .count();
        assertThat(freshDetected).isEqualTo(1);
    }

    // ── Test fakes ─────────────────────────────────────────────────────────

    private static class FakeRepo implements SetupRepositoryPort {
        final Map<UUID, SetupRecommendation> store = new ConcurrentHashMap<>();

        @Override public void save(SetupRecommendation r) { store.put(r.id(), r); }

        @Override public Optional<SetupRecommendation> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override public List<SetupRecommendation> findActiveByInstrument(Instrument instrument) {
            return store.values().stream()
                .filter(s -> s.instrument() == instrument)
                .filter(s -> s.phase() != SetupPhase.CLOSED && s.phase() != SetupPhase.INVALIDATED)
                .toList();
        }

        @Override public List<SetupRecommendation> findByInstrumentAndPhaseSince(
            Instrument instrument, SetupPhase phase, Instant since) {
            return store.values().stream()
                .filter(s -> s.instrument() == instrument)
                .filter(s -> s.phase() == phase)
                .filter(s -> !s.updatedAt().isBefore(since))
                .toList();
        }

        @Override public void updatePhase(UUID id, SetupPhase phase, Instant updatedAt) {
            SetupRecommendation existing = store.get(id);
            if (existing != null) store.put(id, existing.withPhase(phase, updatedAt));
        }
    }

    private static class RecordingNotifier implements SetupNotificationPort {
        final List<SetupRecommendation> published = new CopyOnWriteArrayList<>();
        @Override public void publish(Instrument instrument, SetupRecommendation r) {
            published.add(r);
        }
    }
}
