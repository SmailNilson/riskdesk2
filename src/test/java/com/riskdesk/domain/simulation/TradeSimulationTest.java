package com.riskdesk.domain.simulation;

import com.riskdesk.domain.model.TradeSimulationStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeSimulationTest {

    private static final Instant NOW = Instant.parse("2026-04-20T12:00:00Z");

    @Test
    void rejectsNullReviewType() {
        assertThatThrownBy(() -> new TradeSimulation(
            null, 1L, null, "MNQ", "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            null, null, null, null, null, null,
            NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankInstrument() {
        assertThatThrownBy(() -> new TradeSimulation(
            null, 1L, ReviewType.SIGNAL, "  ", "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            null, null, null, null, null, null,
            NOW
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("instrument");
    }

    @Test
    void rejectsBlankAction() {
        assertThatThrownBy(() -> new TradeSimulation(
            null, 1L, ReviewType.SIGNAL, "MNQ", "",
            TradeSimulationStatus.PENDING_ENTRY,
            null, null, null, null, null, null,
            NOW
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("action");
    }

    @Test
    void rejectsNullStatusAndCreatedAt() {
        assertThatThrownBy(() -> new TradeSimulation(
            null, 1L, ReviewType.SIGNAL, "MNQ", "LONG",
            null,
            null, null, null, null, null, null,
            NOW
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new TradeSimulation(
            null, 1L, ReviewType.SIGNAL, "MNQ", "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            null, null, null, null, null, null,
            null
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructsWithAllNullableFields() {
        TradeSimulation sim = new TradeSimulation(
            null, 42L, ReviewType.SIGNAL, "MNQ", "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            null, null, null, null, null, null,
            NOW
        );

        assertThat(sim.id()).isNull();
        assertThat(sim.reviewId()).isEqualTo(42L);
        assertThat(sim.reviewType()).isEqualTo(ReviewType.SIGNAL);
        assertThat(sim.simulationStatus()).isEqualTo(TradeSimulationStatus.PENDING_ENTRY);
        assertThat(sim.createdAt()).isEqualTo(NOW);
    }

    @Test
    void withStatusReturnsCopyWithUpdatedStatusAndResolution() {
        Instant resolvedAt = Instant.parse("2026-04-20T14:30:00Z");
        TradeSimulation active = new TradeSimulation(
            7L, 42L, ReviewType.AUDIT, "MCL", "SHORT",
            TradeSimulationStatus.ACTIVE,
            NOW, null,
            new BigDecimal("0.150"), null, null, new BigDecimal("80.20"),
            NOW
        );

        TradeSimulation won = active.withStatus(TradeSimulationStatus.WIN, resolvedAt);

        assertThat(won.id()).isEqualTo(7L);
        assertThat(won.reviewId()).isEqualTo(42L);
        assertThat(won.simulationStatus()).isEqualTo(TradeSimulationStatus.WIN);
        assertThat(won.resolutionTime()).isEqualTo(resolvedAt);
        assertThat(won.activationTime()).isEqualTo(NOW);
        assertThat(won.bestFavorablePrice()).isEqualByComparingTo("80.20");
        // original untouched
        assertThat(active.simulationStatus()).isEqualTo(TradeSimulationStatus.ACTIVE);
        assertThat(active.resolutionTime()).isNull();
    }
}
