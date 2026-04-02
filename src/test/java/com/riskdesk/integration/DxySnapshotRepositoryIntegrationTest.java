package com.riskdesk.integration;

import com.riskdesk.infrastructure.persistence.MarketDxySnapshotJpaRepository;
import com.riskdesk.infrastructure.persistence.entity.MarketDxySnapshotEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class DxySnapshotRepositoryIntegrationTest {

    @Autowired
    private MarketDxySnapshotJpaRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void latestComplete_returnsNewestSnapshot() {
        repository.save(entity("2026-04-01T10:00:00Z", "103.10000000"));
        repository.save(entity("2026-04-01T10:01:00Z", "103.20000000"));

        var latest = repository.findTopByCompleteTrueOrderByTimestampDesc();

        assertTrue(latest.isPresent());
        assertEquals(Instant.parse("2026-04-01T10:01:00Z"), latest.get().getTimestamp());
    }

    @Test
    void betweenRange_returnsAscendingSnapshots() {
        repository.save(entity("2026-04-01T10:00:00Z", "103.10000000"));
        repository.save(entity("2026-04-01T10:01:00Z", "103.20000000"));
        repository.save(entity("2026-04-01T10:02:00Z", "103.30000000"));

        List<MarketDxySnapshotEntity> history = repository.findByCompleteTrueAndTimestampBetweenOrderByTimestampAsc(
            Instant.parse("2026-04-01T10:00:30Z"),
            Instant.parse("2026-04-01T10:02:00Z")
        );

        assertEquals(2, history.size());
        assertEquals(Instant.parse("2026-04-01T10:01:00Z"), history.get(0).getTimestamp());
        assertEquals(Instant.parse("2026-04-01T10:02:00Z"), history.get(1).getTimestamp());
    }

    private MarketDxySnapshotEntity entity(String timestamp, String dxyValue) {
        MarketDxySnapshotEntity entity = new MarketDxySnapshotEntity();
        entity.setTimestamp(Instant.parse(timestamp));
        entity.setEurusd(new BigDecimal("1.08110000"));
        entity.setUsdjpy(new BigDecimal("149.22000000"));
        entity.setGbpusd(new BigDecimal("1.26220000"));
        entity.setUsdcad(new BigDecimal("1.35120000"));
        entity.setUsdsek(new BigDecimal("10.48050000"));
        entity.setUsdchf(new BigDecimal("0.90215000"));
        entity.setDxyValue(new BigDecimal(dxyValue));
        entity.setSource("IBKR_SYNTHETIC");
        entity.setComplete(true);
        return entity;
    }
}
