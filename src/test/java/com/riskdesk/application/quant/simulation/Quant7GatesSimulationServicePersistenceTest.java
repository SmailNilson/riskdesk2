package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationPublisher;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationStatus;
import com.riskdesk.domain.quant.simulation.port.Quant7GatesSimulationRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the durable-store wiring: OPEN/CLOSE rows are mirrored into the
 * repository port, reads come from the store (not the capped in-memory
 * buckets), and restart rehydration seeds the id sequence and reloads
 * in-flight OPEN rows.
 */
class Quant7GatesSimulationServicePersistenceTest {

    @Test
    void persistsOpenThenUpdatesOnClose() {
        FakeRepo repo = new FakeRepo();
        Quant7GatesSimulationService service =
            new Quant7GatesSimulationService(emptyPublisher(), repoProvider(repo));
        service.resetForTesting();

        // Open LONG → one OPEN row persisted.
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), absorptionHaussiereHighConf());
        assertThat(repo.store.values())
            .singleElement()
            .satisfies(r -> {
                assertThat(r.status()).isEqualTo(Quant7GatesSimulationStatus.OPEN);
                assertThat(r.direction()).isEqualTo(Quant7GatesSimulation.Direction.LONG);
            });

        // Price hits TP1 (+40) → same row upserted as CLOSED_TP1 with positive P&L.
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25 + 45.0), absorptionHaussiereHighConf());
        assertThat(repo.store.values())
            .anyMatch(r -> r.status() == Quant7GatesSimulationStatus.CLOSED_TP1
                && r.pnlPoints() != null && r.pnlPoints() > 0);
    }

    @Test
    void statsAndListAllReadFullHistoryFromStoreBeyondMemoryCap() {
        FakeRepo repo = new FakeRepo();
        // Pre-seed 60 closed rows directly — more than the in-memory cap (50).
        for (long i = 1; i <= 60; i++) {
            boolean win = i % 2 == 0;
            repo.store.put(i, closedRow(i, win));
        }
        Quant7GatesSimulationService service =
            new Quant7GatesSimulationService(emptyPublisher(), repoProvider(repo));
        service.resetForTesting();

        Quant7GatesSimulationService.Stats stats = service.stats();
        assertThat(stats.closedCount()).isEqualTo(60);
        assertThat(stats.wins()).isEqualTo(30);
        assertThat(stats.losses()).isEqualTo(30);
        assertThat(service.listAll()).hasSize(60);
    }

    @Test
    void rehydrateSeedsSequencePastMaxIdAndReloadsOpenRows() {
        FakeRepo repo = new FakeRepo();
        repo.store.put(10L, closedRow(10L, true));
        repo.store.put(42L, openRow(42L, Instrument.MGC, Quant7GatesSimulation.Direction.LONG));

        Quant7GatesSimulationService service =
            new Quant7GatesSimulationService(emptyPublisher(), repoProvider(repo));
        service.rehydrate();

        // In-flight OPEN row reloaded into the live set.
        assertThat(service.listOpen())
            .anyMatch(r -> r.id() == 42L && r.instrument() == Instrument.MGC);

        // Sequence seeded past max id (42) → next opened row gets id 43.
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), absorptionHaussiereHighConf());
        assertThat(service.listOpen())
            .filteredOn(r -> r.instrument() == Instrument.MNQ)
            .singleElement()
            .satisfies(r -> assertThat(r.id()).isEqualTo(43L));
    }

    // ── fakes & helpers ──────────────────────────────────────────────────────

    private static final class FakeRepo implements Quant7GatesSimulationRepositoryPort {
        final Map<Long, Quant7GatesSimulation> store = new LinkedHashMap<>();

        @Override public void save(Quant7GatesSimulation s) { store.put(s.id(), s); }

        @Override public List<Quant7GatesSimulation> findAllClosed() {
            List<Quant7GatesSimulation> out = new ArrayList<>();
            for (Quant7GatesSimulation s : store.values()) if (!s.isOpen()) out.add(s);
            return out;
        }

        @Override public List<Quant7GatesSimulation> findAllOpen() {
            List<Quant7GatesSimulation> out = new ArrayList<>();
            for (Quant7GatesSimulation s : store.values()) if (s.isOpen()) out.add(s);
            return out;
        }

        @Override public long maxId() {
            return store.keySet().stream().mapToLong(Long::longValue).max().orElse(0L);
        }
    }

    private static Quant7GatesSimulation closedRow(long id, boolean win) {
        double pts = win ? 40.0 : -25.0;
        return new Quant7GatesSimulation(
            id, Instrument.MNQ, Quant7GatesSimulation.Direction.LONG,
            29687.25, 29662.25, 29727.25, 29767.25,
            Instant.parse("2026-05-22T10:00:00Z"), "LONG · test", "LIVE_PUSH",
            win ? Quant7GatesSimulationStatus.CLOSED_TP1 : Quant7GatesSimulationStatus.CLOSED_SL,
            29687.25 + pts, "LIVE_PUSH", Instant.parse("2026-05-22T11:00:00Z"),
            win ? "TP1 hit" : "SL hit", pts, pts * 2.0);
    }

    private static Quant7GatesSimulation openRow(long id, Instrument instr, Quant7GatesSimulation.Direction dir) {
        return new Quant7GatesSimulation(
            id, instr, dir, 4000.0, 3990.0, 4010.0, 4020.0,
            Instant.parse("2026-05-22T10:00:00Z"), dir + " · test", "LIVE_PUSH",
            Quant7GatesSimulationStatus.OPEN, null, "", null, null, 0.0, 0.0);
    }

    private static PatternAnalysis absorptionHaussiereHighConf() {
        return new PatternAnalysis(
            OrderFlowPattern.ABSORPTION_HAUSSIERE,
            "Absorption haussière",
            "Δ=-864 mais prix +12.3pts | Confirmations: [Δ CONFIRMED][ABS BULL ACTIVE]",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.AVOID // SHORT view; LONG mirror = TRADE
        );
    }

    private static QuantSnapshot snapshotAt(double price) {
        Map<Gate, GateResult> gates = new EnumMap<>(Gate.class);
        return new QuantSnapshot(
            Instrument.MNQ, gates, 4, 4,
            price, "LIVE_PUSH", 0.0,
            ZonedDateTime.now(ZoneId.of("America/New_York"))
        );
    }

    private static ObjectProvider<Quant7GatesSimulationPublisher> emptyPublisher() {
        return new ObjectProvider<>() {
            @Override public Quant7GatesSimulationPublisher getObject() { return null; }
            @Override public Quant7GatesSimulationPublisher getObject(Object... args) { return null; }
            @Override public Quant7GatesSimulationPublisher getIfAvailable() { return null; }
            @Override public Quant7GatesSimulationPublisher getIfUnique() { return null; }
        };
    }

    private static ObjectProvider<Quant7GatesSimulationRepositoryPort> repoProvider(Quant7GatesSimulationRepositoryPort repo) {
        return new ObjectProvider<>() {
            @Override public Quant7GatesSimulationRepositoryPort getObject() { return repo; }
            @Override public Quant7GatesSimulationRepositoryPort getObject(Object... args) { return repo; }
            @Override public Quant7GatesSimulationRepositoryPort getIfAvailable() { return repo; }
            @Override public Quant7GatesSimulationRepositoryPort getIfUnique() { return repo; }
        };
    }
}
