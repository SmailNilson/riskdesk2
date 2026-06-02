package com.riskdesk.integration;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationStatus;
import com.riskdesk.infrastructure.quant.simulation.persistence.Quant7GatesSimulationJpaAdapter;
import com.riskdesk.infrastructure.quant.simulation.persistence.Quant7GatesSimulationJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the durable store for the Quant 7-Gates harness against H2:
 * upsert-by-id, closed/open partitioning, and the {@code max(id)} seed query.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(Quant7GatesSimulationJpaAdapter.class)
class Quant7GatesSimulationRepositoryIntegrationTest {

    @Autowired
    private Quant7GatesSimulationJpaAdapter adapter;

    @Autowired
    private Quant7GatesSimulationJpaRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void savePersistsOpenRowAndUpsertOnCloseKeepsSingleRow() {
        Quant7GatesSimulation open = openRow(1L);
        adapter.save(open);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(adapter.findAllOpen()).singleElement()
            .satisfies(r -> assertThat(r.status()).isEqualTo(Quant7GatesSimulationStatus.OPEN));
        assertThat(adapter.findAllClosed()).isEmpty();

        // Close the same id → upsert, not insert.
        Quant7GatesSimulation closed = open.close(
            29727.25, "LIVE_PUSH", Instant.parse("2026-05-22T11:00:00Z"),
            "TP1 hit", Quant7GatesSimulationStatus.CLOSED_TP1);
        adapter.save(closed);

        assertThat(repository.count()).isEqualTo(1);
        assertThat(adapter.findAllOpen()).isEmpty();
        assertThat(adapter.findAllClosed()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(1L);
            assertThat(r.status()).isEqualTo(Quant7GatesSimulationStatus.CLOSED_TP1);
            assertThat(r.exitPrice()).isEqualTo(29727.25);
            assertThat(r.pnlPoints()).isNotNull().isGreaterThan(0.0);
            assertThat(r.closedAt()).isNotNull();
        });
    }

    @Test
    void maxIdReturnsZeroWhenEmptyAndHighestIdOtherwise() {
        assertThat(adapter.maxId()).isZero();
        adapter.save(openRow(5L));
        adapter.save(openRow(42L));
        assertThat(adapter.maxId()).isEqualTo(42L);
    }

    @Test
    void findAllClosedReturnsEveryResolvedRow() {
        adapter.save(openRow(1L));
        adapter.save(closedRow(2L));
        adapter.save(closedRow(3L));
        List<Quant7GatesSimulation> closed = adapter.findAllClosed();
        assertThat(closed).hasSize(2)
            .allMatch(r -> r.status() != Quant7GatesSimulationStatus.OPEN);
    }

    private static Quant7GatesSimulation openRow(long id) {
        return new Quant7GatesSimulation(
            id, Instrument.MNQ, Quant7GatesSimulation.Direction.LONG,
            29687.25, 29662.25, 29727.25, 29767.25,
            Instant.parse("2026-05-22T10:00:00Z"), "LONG · test", "LIVE_PUSH",
            Quant7GatesSimulationStatus.OPEN, null, "", null, null, 0.0, 0.0);
    }

    private static Quant7GatesSimulation closedRow(long id) {
        return openRow(id).close(
            29727.25, "LIVE_PUSH", Instant.parse("2026-05-22T11:00:00Z"),
            "TP1 hit", Quant7GatesSimulationStatus.CLOSED_TP1);
    }
}
