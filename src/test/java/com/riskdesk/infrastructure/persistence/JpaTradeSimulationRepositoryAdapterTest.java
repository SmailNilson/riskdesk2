package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.domain.simulation.TradeSimulation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaTradeSimulationRepositoryAdapter.class)
class JpaTradeSimulationRepositoryAdapterTest {

    private static final Instant NOW = Instant.parse("2026-04-20T12:00:00Z");

    @Autowired
    private JpaTradeSimulationRepositoryAdapter adapter;

    @Autowired
    private JpaTradeSimulationRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void save_roundTripsAllFields() {
        TradeSimulation saved = adapter.save(activeSimulation(100L, "MNQ", ReviewType.SIGNAL));
        assertThat(saved.id()).isNotNull();

        TradeSimulation loaded = adapter.findByReviewId(100L, ReviewType.SIGNAL).orElseThrow();
        assertThat(loaded.id()).isEqualTo(saved.id());
        assertThat(loaded.reviewId()).isEqualTo(100L);
        assertThat(loaded.reviewType()).isEqualTo(ReviewType.SIGNAL);
        assertThat(loaded.instrument()).isEqualTo("MNQ");
        assertThat(loaded.action()).isEqualTo("LONG");
        assertThat(loaded.simulationStatus()).isEqualTo(TradeSimulationStatus.ACTIVE);
        assertThat(loaded.activationTime()).isEqualTo(NOW);
        assertThat(loaded.maxDrawdownPoints()).isEqualByComparingTo("12.50");
        assertThat(loaded.trailingStopResult()).isEqualTo("TRAILING_BE");
        assertThat(loaded.trailingExitPrice()).isEqualByComparingTo("18123.25");
        assertThat(loaded.bestFavorablePrice()).isEqualByComparingTo("18150.75");
        assertThat(loaded.createdAt()).isEqualTo(NOW);
    }

    @Test
    void findByReviewId_isScopedByReviewType() {
        adapter.save(activeSimulation(200L, "MNQ", ReviewType.SIGNAL));
        adapter.save(activeSimulation(200L, "MNQ", ReviewType.AUDIT));

        Optional<TradeSimulation> signal = adapter.findByReviewId(200L, ReviewType.SIGNAL);
        Optional<TradeSimulation> audit = adapter.findByReviewId(200L, ReviewType.AUDIT);

        assertThat(signal).isPresent();
        assertThat(signal.get().reviewType()).isEqualTo(ReviewType.SIGNAL);
        assertThat(audit).isPresent();
        assertThat(audit.get().reviewType()).isEqualTo(ReviewType.AUDIT);
        assertThat(signal.get().id()).isNotEqualTo(audit.get().id());
    }

    @Test
    void findByInstrument_returnsMostRecentFirstAndLimits() {
        adapter.save(simulation(301L, "MNQ", TradeSimulationStatus.WIN,
            Instant.parse("2026-04-20T10:00:00Z")));
        adapter.save(simulation(302L, "MNQ", TradeSimulationStatus.LOSS,
            Instant.parse("2026-04-20T11:00:00Z")));
        adapter.save(simulation(303L, "MNQ", TradeSimulationStatus.ACTIVE,
            Instant.parse("2026-04-20T12:00:00Z")));
        adapter.save(simulation(304L, "MCL", TradeSimulationStatus.ACTIVE,
            Instant.parse("2026-04-20T12:30:00Z")));

        List<TradeSimulation> mnq = adapter.findByInstrument("MNQ", 2);

        assertThat(mnq).hasSize(2);
        assertThat(mnq).extracting(TradeSimulation::reviewId)
            .containsExactly(303L, 302L);
    }

    @Test
    void findByStatuses_filtersCorrectly() {
        adapter.save(simulation(401L, "MNQ", TradeSimulationStatus.PENDING_ENTRY, NOW));
        adapter.save(simulation(402L, "MNQ", TradeSimulationStatus.ACTIVE, NOW));
        adapter.save(simulation(403L, "MCL", TradeSimulationStatus.WIN, NOW));
        adapter.save(simulation(404L, "MCL", TradeSimulationStatus.CANCELLED, NOW));

        List<TradeSimulation> open = adapter.findByStatuses(
            List.of(TradeSimulationStatus.PENDING_ENTRY, TradeSimulationStatus.ACTIVE)
        );

        assertThat(open).extracting(TradeSimulation::reviewId)
            .containsExactlyInAnyOrder(401L, 402L);

        assertThat(adapter.findByStatuses(List.of())).isEmpty();
    }

    @Test
    void findRecent_isOrderedAndLimited() {
        adapter.save(simulation(501L, "MNQ", TradeSimulationStatus.WIN,
            Instant.parse("2026-04-20T09:00:00Z")));
        adapter.save(simulation(502L, "MCL", TradeSimulationStatus.LOSS,
            Instant.parse("2026-04-20T10:00:00Z")));
        adapter.save(simulation(503L, "MGC", TradeSimulationStatus.ACTIVE,
            Instant.parse("2026-04-20T11:00:00Z")));

        List<TradeSimulation> recent = adapter.findRecent(2);

        assertThat(recent).hasSize(2);
        assertThat(recent).extracting(TradeSimulation::reviewId)
            .containsExactly(503L, 502L);
    }

    private TradeSimulation activeSimulation(long reviewId, String instrument, ReviewType type) {
        return new TradeSimulation(
            null,
            reviewId,
            type,
            instrument,
            "LONG",
            TradeSimulationStatus.ACTIVE,
            NOW,
            null,
            new BigDecimal("12.50"),
            "TRAILING_BE",
            new BigDecimal("18123.25"),
            new BigDecimal("18150.75"),
            NOW
        );
    }

    private TradeSimulation simulation(long reviewId,
                                        String instrument,
                                        TradeSimulationStatus status,
                                        Instant createdAt) {
        return new TradeSimulation(
            null,
            reviewId,
            ReviewType.SIGNAL,
            instrument,
            "LONG",
            status,
            null,
            null,
            null,
            null,
            null,
            null,
            createdAt
        );
    }
}
