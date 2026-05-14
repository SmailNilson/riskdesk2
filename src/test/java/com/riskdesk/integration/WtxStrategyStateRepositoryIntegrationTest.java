package com.riskdesk.integration;

import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.engine.strategy.wtx.WtxProfile;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.infrastructure.persistence.JpaWtxStrategyStateAdapter;
import com.riskdesk.infrastructure.persistence.JpaWtxStrategyStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaWtxStrategyStateAdapter.class)
class WtxStrategyStateRepositoryIntegrationTest {

    @Autowired
    private JpaWtxStrategyStateAdapter adapter;

    @Autowired
    private JpaWtxStrategyStateRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void fiveMinuteAndTenMinuteStatesCoexistIndependently() {
        WtxStrategyState fiveMin = WtxStrategyState.initial("MNQ", "5m", BigDecimal.valueOf(10_000))
                .withProfile(WtxProfile.BASELINE)
                .withPosition(WtxPosition.LONG, BigDecimal.valueOf(20000), BigDecimal.valueOf(2));
        WtxStrategyState tenMin = WtxStrategyState.initial("MNQ", "10m", BigDecimal.valueOf(10_000))
                .withProfile(WtxProfile.STRICT)
                .withAutoExecution(true);

        adapter.save(fiveMin);
        adapter.save(tenMin);

        assertThat(repository.count()).isEqualTo(2);

        WtxStrategyState loaded5m = adapter.load("MNQ", "5m").orElseThrow();
        WtxStrategyState loaded10m = adapter.load("MNQ", "10m").orElseThrow();

        // The 5m position must not leak into the 10m state, and vice-versa.
        assertThat(loaded5m.currentPosition()).isEqualTo(WtxPosition.LONG);
        assertThat(loaded5m.activeProfile()).isEqualTo(WtxProfile.BASELINE);
        assertThat(loaded5m.autoExecutionEnabled()).isFalse();

        assertThat(loaded10m.currentPosition()).isEqualTo(WtxPosition.FLAT);
        assertThat(loaded10m.activeProfile()).isEqualTo(WtxProfile.STRICT);
        assertThat(loaded10m.autoExecutionEnabled()).isTrue();
    }

    @Test
    void saveUpdatesExistingRowForSameInstrumentAndTimeframe() {
        adapter.save(WtxStrategyState.initial("MNQ", "10m", BigDecimal.valueOf(10_000)));
        adapter.save(WtxStrategyState.initial("MNQ", "10m", BigDecimal.valueOf(10_000))
                .withProfile(WtxProfile.HTF));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(adapter.load("MNQ", "10m").orElseThrow().activeProfile()).isEqualTo(WtxProfile.HTF);
    }
}
