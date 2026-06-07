package com.riskdesk.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.engine.strategy.wtx.WtxAction;
import com.riskdesk.domain.engine.strategy.wtx.WtxEnrichmentSnapshot;
import com.riskdesk.domain.engine.strategy.wtx.WtxExitType;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignal;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the WTX signal-history adapter round-trips the {@code realizedPnl} column —
 * non-null on a close row (so the per-day P&L total can sum it), null on an open row.
 */
@DataJpaTest
@ActiveProfiles("test")
class JpaWtxSignalHistoryAdapterTest {

    private static final Instant TS = Instant.parse("2026-06-05T19:00:00Z");

    @Autowired
    private JpaWtxSignalHistoryRepository repository;

    private JpaWtxSignalHistoryAdapter adapter;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        adapter = new JpaWtxSignalHistoryAdapter(repository, new ObjectMapper());
    }

    @Test
    void save_roundTripsRealizedPnlOnClose() {
        adapter.save(closeSignal(new BigDecimal("125.50")));

        List<WtxSignal> recent = adapter.findRecent("MCL", 10);

        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).realizedPnl()).isEqualByComparingTo("125.50");
        assertThat(recent.get(0).exitType()).isEqualTo(WtxExitType.REVERSE);
    }

    @Test
    void save_leavesRealizedPnlNullOnOpen() {
        adapter.save(openSignal());

        List<WtxSignal> recent = adapter.findRecent("MCL", 10);

        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).realizedPnl()).isNull();
    }

    private WtxSignal openSignal() {
        return new WtxSignal("MCL", "5m", WtxSignalType.COMPRA, "LONG",
                new BigDecimal("1"), new BigDecimal("0"), true, WtxAction.OPEN_LONG,
                WtxEnrichmentSnapshot.empty(), TS,
                null, null, new BigDecimal("60.00"), null, null);
    }

    private WtxSignal closeSignal(BigDecimal realizedPnl) {
        return new WtxSignal("MCL", "5m", WtxSignalType.VENTA, "SHORT",
                new BigDecimal("1"), new BigDecimal("0"), true, WtxAction.REVERSE_TO_SHORT,
                WtxEnrichmentSnapshot.empty(), TS,
                null, null, new BigDecimal("61.00"), WtxExitType.REVERSE, realizedPnl);
    }
}
