package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.FlashCrashEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface JpaFlashCrashEventRepository extends JpaRepository<FlashCrashEventEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM FlashCrashEventEntity e WHERE e.timestamp < :cutoff")
    int deleteByTimestampBefore(Instant cutoff);

    /**
     * Returns the most recent persisted phase transition for the given instrument.
     * <p>Used by {@code GET /api/order-flow/flash-crash/status} to seed the UI on
     * a fresh page load — the {@code /topic/flash-crash} WebSocket stream only
     * delivers events going forward, so a freshly-opened panel needs this
     * snapshot before live pushes take over.
     */
    Optional<FlashCrashEventEntity> findFirstByInstrumentOrderByTimestampDesc(Instrument instrument);
}
